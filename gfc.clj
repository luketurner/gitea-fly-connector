#!/usr/bin/env bb

(ns gfc
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [org.httpkit.server :as server]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [taoensso.timbre :as timbre :refer [info error warn debug spy]]))

;;
;; CLI argument parsing
;;

(def cli-options [["-D" "--dev" "Development mode (disables authorization)" :default false :parse-fn #(boolean %)]
                  ["-h" "--help" "Print usage info"]])

(def parsed-args (parse-opts *command-line-args* cli-options))
(def opts (:options parsed-args))

(cond
  (:help opts)
  (do (println "Receives Gitea webhook events and triggers Fly builds. Usage:\n" (:summary parsed-args))
      (System/exit 0))
  
  (:errors parsed-args)
  (do (println "Invalid arguments:\n" (str/join "\n" (:errors parsed-args)))
      (System/exit 1))
  
  :else :continue)

(def DEV? (:dev opts))

;;
;; Environment var loading
;;

(defn b64decode [s] (String. (.decode (java.util.Base64/getDecoder) s)))
(defn env-var-name [var] (str "GFC_" var))

(defmacro defenv
  "Shorthand macro for defining environment variables.
   Note that the name and function signature of this macro are also implicitly depended on by the
   gendocs.clj script, which should be updated if they're changed. (Changing the macro's
   implementation should be safe, though.)"
  [name docstring & {:keys [parse-fn default] :or {parse-fn identity}}]
  `(def ~name ~docstring
     (if-let [v (System/getenv ~(env-var-name name))]
       (try
         (~parse-fn v)
         (catch Exception ex
           (error ex "error parsing env var: " ~(env-var-name name) {:parser ~parse-fn})
           ~default))
       ~default)))

(defenv PORT
  "Port for the HTTP server to listen on."
  :parse-fn Integer/parseInt
  :default 8080)

(defenv ALLOWED_REPO_RE
  "GFC will reject builds if the repository URL doesn't match this regex."
  :parse-fn re-pattern
  :default #".*")

(defenv GIT_USE_SSH
  "Uses Git SSH protocol to fetch repos data. Set to `false` to use
   unauthenticated HTTP(s) instead (works for public repos only)."
  :parse-fn parse-boolean
  :default true)

(defenv MAIN_REF
  "GFC will reject builds if the pushed ref doesn't match this value."
  :default "refs/heads/main")

(defenv REPO_CONFIG_FILE
  "Name of config file within repos. (Currently, per-repo config is
   unused and this variable can be ignored.)"
  :default "gfc.yaml")

(defenv WEBHOOK_SECRET
  "Secret value used to verify Gitea webhook signatures. Should
   be the same value configured as the \"Webhook secret\" in
   your repository settings in Gitea's UI.")

(defenv SSH_PRIVATE_KEY
  "A **base64-encoded** copy of the SSH private key for GFC to use
   to authenticate when using Git SSH. If you have a private key,
   you can base64 encode it for this purpose using
   `base64 -w0 myfilename`."
  :parse-fn b64decode)

(defenv SSH_KEY_FINGERPRINT
  "A purely informational variable, used to keep track of \"which
   SSH key did I deploy it with again?\" Expected to match what
   is contained in the Gitea UI.")

(defenv SSH_ALLOWED_HOSTS
  "A **base64-encoded** list of newline-separated SSH host keys
   in the `known_hosts` file format. (If your Gitea is hosted at
   `git.example.com`, you can generate a value for this variable
   with `ssh-keyscan git.example.com | base64 -w0`)"
  :parse-fn b64decode)

(defenv FLY_TOKEN
  "The Fly.io access token used when deploying your app. Must be
   created in the Fly Web dashboard.")

(defenv MAX_PARALLEL_BUILDS
  "The max. number of parallel builds to be running at once. If
   there are at least this number of builds, future webhook build
   requests will receive a HTTP 429 status code."
  :parse-fn Integer/parseInt
  :default 2)

(defenv DISABLE_DEPLOY
  "When set to `true`, builds will run as normal but the actual
   `fly deploy` step will be skipped. (Useful for low-consequences
   testing.)"
  :parse-fn parse-boolean
  :default false)

(defenv LOG_LEVEL
  "The minimum log level to display when running (one of: `trace`,
   `debug`, `info`, `warn`, `error`, `fatal`, `report`)."
  :parse-fn keyword
  :default :info)

(info "setting log level:" LOG_LEVEL)
(timbre/set-level! LOG_LEVEL)

;;
;; Define temporary files
;;

(defn path [& xs] (str (apply fs/path xs)))

(def app-global-tmpdir (fs/create-temp-dir))
(def ssh-private-key-filename (path app-global-tmpdir "ssh_private_key"))
(def ssh-allowed-hosts-filename (path app-global-tmpdir "ssh_allowed_hosts"))
(def remote-home-dir (path app-global-tmpdir "home"))

;;
;; Global state mgmt
;;

(def current-parallel-builds (atom 0))

(defn try-reserve-parallel-build!
  "Attempts to reserve a build. If we're already at MAX_PARALLEL_BUILDS, the request will fail (returns false).
   Otherwise returns true."
  []
  (let [[old new] (swap-vals! current-parallel-builds (comp (partial min MAX_PARALLEL_BUILDS) inc))]
    ; if new and old aren't equal, we succeeded in incrementing current-parallel-builds
    (not= old new)))

(defn unreserve-parallel-build! [] (swap! current-parallel-builds dec))

;;
;; Functions
;;

(defn load-repo-config
  "Currently vestigial, this loads a per-repo YAML config file for GFC. Would allow GFC's behavior
   to be adjusted by the repository's author, similar to a `.circle.yml` for Circle CI.
   I have plans here, but they're not implemented yet."
  [repo-dir]
  (let [config-path (path repo-dir REPO_CONFIG_FILE)]
    (if-not (fs/regular-file? config-path)
      (debug "No repo config" {:path config-path})
      (do
        (debug "Reading repo config" {:path config-path})
        (-> config-path
            (slurp)
            (yaml/parse-string :keywords false)
            (spy))))))

(defn lsh
  "Wrapper around sh that throws an error if the command returns a nonzero exit code.
   Also logs full STDOUT/STDERR for all commands when debug logging is enabled.
   Note that the input command (the args) are not logged because they can contain secrets."
  [& args]
  (let [{:keys [exit out err] :as result} (apply sh args)]
    (when (not= exit 0) (debug "exit code:" exit))
    (when (not= out "") (debug "OUT" out))
    (when (not= err "") (debug "ERR" err)) 
    (when (> exit 0) (throw (ex-info "error running command" {:exit-code exit :stdout out :stderr err})))
    result))

(defn checkout-remote-commit
  "Given an empty directory `dir`, will initialize a Git repo, add the given `url` as a remote, fetch the specified commit SHA,
   and check it out. This is used instead of `git clone` because it only transfers a single tree (commit) instead of the whole
   repository.
   
   Note that this needs at least Git 2.5.0 on both client and server as well as having the `uploadpack.allowReachableSHA1InWant`
   setting set to `true` on the server side. At least for current versions of Gitea, this Just Works, but YMMV.
   
   Approach adapted from https://stackoverflow.com/questions/31278902.
   Use of GIT_SSH_COMMAND from https://stackoverflow.com/questions/4565700."
  [dir url commit]
  (when-not dir    (throw (Exception. "checkout-remote-commit called with no dir")))
  (when-not url    (throw (Exception. "checkout-remote-commit called with no url")))
  (when-not commit (throw (Exception. "checkout-remote-commit called with no commit")))
  (debug "git" "init" "--quiet" dir)
  (lsh "git" "init" "--quiet" dir)
  (debug "git" "remote" "add" "origin" url)
  (lsh "git" "remote" "add" "origin" url :dir dir)
  (debug "git" "fetch" "--depth" "1" "origin" commit)
  (lsh "git" "fetch" "--depth" "1" "origin" commit
       :env {"GIT_SSH_COMMAND" (str "ssh -i " ssh-private-key-filename " -o IdentitiesOnly=yes -o GlobalKnownHostsFile=" ssh-allowed-hosts-filename)}
       :dir dir)
  (debug "git" "checkout" "FETCH_HEAD")
  (lsh "git" "checkout" "FETCH_HEAD" :dir dir))

(defn deploy-to-fly
  "Given a directory `dir` which is expected to contain a Fly application, this function
   will execute `fly deploy --remote-only` in it."
  [dir]
  (when-not dir (throw (Exception. "deploy-to-fly called with no dir")))
  (if DISABLE_DEPLOY
    (info "Skipping deployment since" (env-var-name "DISABLE_DEPLOY") "is set.")
    (do
      (debug "fly" "deploy" "--remote-only")
      (lsh "fly" "deploy" "--remote-only"
           :env {"FLY_API_TOKEN" FLY_TOKEN
                 "HOME" remote-home-dir
                 "PATH" "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"}
           :dir dir))))

(defn execute-pre-deploy-steps [repo-dir]
  (let [repo-config (load-repo-config repo-dir)
        {secrets "secrets"
         volumes "volumes"
         certs   "certs"} repo-config]
    (when certs (warn "'certs' section not yet supported"))
    (when secrets (warn "'secrets' section not yet supported"))
    (when volumes (warn "'volumes' section not yet supported"))))

(defn build-commit
  "Checks out the given repo at the given commit and executes a build in the
   repo's root directory."
  [repo-url commit]
  (when-not repo-url (throw (Exception. "build-commit called with no repo-url")))
  (when-not commit   (throw (Exception. "build-commit called with no commit")))
  (fs/with-temp-dir [dir {}]
    (let [dir (str dir)]
      (checkout-remote-commit dir repo-url commit)
      (execute-pre-deploy-steps dir)
      (deploy-to-fly dir))))

(defn parse-gitea-webhook-body [body]
  (let [body (json/parse-string body)]
    {:repo-url (get-in body ["repository" (if GIT_USE_SSH "ssh_url" "clone_url")])
     :commit (get body "after")
     :ref (get body "ref")}))

(defn handle-webhook-request
  "Processes a webhook's HTTP request, validating body fields and, if everything looks
   good, running a build."
  [req]
  (let [{:keys [repo-url commit ref]} (parse-gitea-webhook-body (:body req))
        event-type (get-in req [:headers "x-gitea-event-type"])
        ; TODO -- handle delivery IDs
        delivery-id (get-in req [:headers "x-gitea-delivery"])
        allowed-repo-url? (re-matches ALLOWED_REPO_RE repo-url)]
    (info "Processing webhook" {:repo-url repo-url :commit commit :delivery-id delivery-id :event event-type :ref ref})
    (if-not allowed-repo-url?
      (do
        (debug "Build skipped: repo URL not allowed" {:allowed-repos ALLOWED_REPO_RE :repo-url repo-url})
        {:status 400 :body "Sorry, I just can't work with you on this."})
      (if-not (= event-type "push")
        (do
          (debug "Build skipped: unsupported event type" {:expected "push" :received event-type})
          {:status 200 :body "Nothing to do for this event type."})
        (if-not (= ref MAIN_REF)
          (do
            (debug "Build skipped: non-deployable ref" {:deployable-ref MAIN_REF :this-ref ref})
            {:status 200 :body "Nothing to do for this ref."}) 
          (if-not (try-reserve-parallel-build!)
            (do
              (debug "Build skipped: max parallel builds reached" {:current @current-parallel-builds :max MAX_PARALLEL_BUILDS})
              {:status 429 :body "Feeling a bit tired today, can we chat later?"})
            (try
              (build-commit repo-url commit)
              (info "Build succeeded!")
              {:status 200 :body "I did the thing!"}
              (catch Exception ex
                (error ex "error building commit" {:repo-url repo-url :commit commit})
                {:status 500 :body "Running into a bit of an issue here. Can we take this offline?"})
              (finally (unreserve-parallel-build!)))))))))

(defn hmac
  "An hmac function that is intended to work exactly the same as the hash_hmac() function in PHP
   so our generated signatures exactly match those generated by Gitea.
   See Gitea docs: https://docs.gitea.io/en-us/webhooks/.
   The code here is adapted from https://stackoverflow.com/questions/15443781/."
  [key string]
  (let [mac (javax.crypto.Mac/getInstance "HMACSHA256")
        secretKey (javax.crypto.spec.SecretKeySpec. (.getBytes key) (.getAlgorithm mac))]
    (apply str
           (map #(format "%02x" %)
                (-> (doto mac
                      (.init secretKey)
                      (.update (.getBytes string)))
                    .doFinal)))))

(defn wrap-is-authenticated
  "Checks that the request's X-Gitea-Signature header is present and valid
   by calculating our own signature of the body of the request and seeing
   that they match."
  [handler]
  (fn [req]
    (let [sig    (get-in req [:headers "x-gitea-signature"])
          body   (:body req)
          key    WEBHOOK_SECRET
          sig2   (hmac key body)
          authenticated? (and sig sig2 (= sig sig2))]
      (if (or DEV? authenticated?)
        (handler req)
        (do
          (debug "Auth failure: signature doesn't match." {:theirs sig :ours sig2})
          {:status 400 :body "Who do you think you are? I AM!"})))))

(defn wrap-request-logger [handler]
  (fn [req]
    (info "Starting request"
          (select-keys req [:remote-addr :server-port :content-length :websocket? :content-type :character-encoding :uri :server-name :query-string :scheme :request-method]))
    (let [resp (handler req)]
      (info "Finished request"
       (select-keys resp [:status]))
      resp)))

(defn wrap-body-string [handler]
  (fn [req]
    (handler (assoc req :body (slurp (:body req))))))

(defn app []
  (wrap-request-logger
   (wrap-body-string
    (wrap-is-authenticated
     handle-webhook-request))))

;;
;; Startup
;;


(info "== gitea-fly-connector ==")
(info)
(info "Config settings:")
(info "           Server Port:" PORT)
(info "         Ref to deploy:" MAIN_REF)
(info "  Allowed repositories:" ALLOWED_REPO_RE)
(info "        Clone URL type:" (if GIT_USE_SSH "ssh" "https"))
(info "  Repo config filename:" REPO_CONFIG_FILE)
(info "   Max parallel builds:" MAX_PARALLEL_BUILDS)
(info "       Deploys enabled:" (not DISABLE_DEPLOY))
(info "             Log level:" LOG_LEVEL)
(info)
(info "Secrets:")
(info "         Fly API Token:" (if FLY_TOKEN "set" "NOT SET"))
(info "  Gitea Webhook secret:" (if WEBHOOK_SECRET "set" "NOT SET"))
(info "       SSH private key:" (if SSH_PRIVATE_KEY "set" "NOT SET"))
(info "       SSH fingerprint:" (or SSH_KEY_FINGERPRINT "NOT SET"))
(info "     SSH Allowed Hosts:" (or SSH_ALLOWED_HOSTS "NOT SET"))
(info)

(when SSH_PRIVATE_KEY
  (info "Writing SSH key to file:" ssh-private-key-filename)
  (try
    (spit ssh-private-key-filename SSH_PRIVATE_KEY)
    (lsh "chmod" "600" ssh-private-key-filename)
    (catch Exception ex
      (error ex "could not write SSH key to file"))))

(when SSH_ALLOWED_HOSTS
  (info "Writing SSH Host key to file:" ssh-allowed-hosts-filename)
  (try
    (spit ssh-allowed-hosts-filename SSH_ALLOWED_HOSTS)
    (catch Exception ex
      (error ex "could not write host key to file"))))

(info "Listening on port" PORT)
(server/run-server (app) {:port PORT})

@(promise)