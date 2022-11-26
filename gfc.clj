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

(defn b64decode [s]
  (String. (.decode (java.util.Base64/getDecoder) s)))

(defn env-var-name [var] (str "GFC_" var))
(defn env-var [var] (System/getenv (env-var-name var)))
(defn env-var-base64 [var] 
  (try
    (when-let [x (env-var var)] (b64decode x))
    (catch Exception ex
      (error ex "error loading env var (expected base64 encoded value)" (env-var-name var)))))
(defn env-var-default [var default] (or (env-var var) default))

(def PORT (Integer/parseInt (env-var-default "PORT" "8080")))
(def ALLOWED-REPO-RE (re-pattern (env-var-default "ALLOWED_REPO_RE" ".*")))
(def GIT-USE-SSH (not= "false" (env-var-default "GIT_USE_SSH" "true")))
(def MAIN-REF (env-var-default "MAIN_REF" "refs/heads/main"))
(def REPO-CONFIG-FILE (env-var-default "REPO_CONFIG_FILE" "gfc.yaml"))
(def WEBHOOK-SECRET (env-var "WEBHOOK_SECRET"))
(def SSH-PRIVATE-KEY (env-var-base64 "SSH_PRIVATE_KEY"))
(def SSH-KEY-FINGERPRINT (env-var "SSH_FINGERPRINT"))
(def SSH-ALLOWED-HOSTS (env-var-base64 "SSH_ALLOWED_HOSTS"))

(def FLY-DEPLOY-SECRET (env-var "FLY_TOKEN"))
(def MAX-PARALLEL-BUILDS (Integer/parseInt (env-var-default "MAX_PARALLEL_BUILDS" "2")))
(def DISABLE-DEPLOY (= "true" (env-var "DISABLE_DEPLOY")))
(def LOG-LEVEL (env-var-default "LOG_LEVEL" "info"))

(timbre/set-level! (keyword LOG-LEVEL))

;;
;; Global state
;;

(def APP-GLOBAL-TMPDIR (fs/create-temp-dir))
(def SSH-KEY-FILENAME (str (fs/path APP-GLOBAL-TMPDIR "gfc_ssh_key")))
(def SSH-KNOWN-HOSTS-FILENAME (str (fs/path APP-GLOBAL-TMPDIR "known_hosts")))

(def REMOTE-HOME (str (fs/path APP-GLOBAL-TMPDIR "home")))

(def current-parallel-builds (atom 0))

;;
;; Functions
;;

(defn load-repo-config [repo-dir]
  (let [config-path (fs/path repo-dir REPO-CONFIG-FILE)]
    (if-not (fs/regular-file? config-path)
      (debug "No repo config" {:path (str config-path)})
      (do
        (debug "Reading repo config" {:path (str config-path)})
        (-> config-path
            (slurp)
            (yaml/parse-string :keywords false)
            (spy))))))

(defn lsh [& args]
  (let [{:keys [exit out err] :as result} (apply sh args)]
    (when (not= exit 0) (debug "exit code:" exit))
    (when (not= out "") (debug "OUT" out))
    (when (not= err "") (debug "ERR" err)) 
    (when (> exit 0) (throw (ex-info "error running command" {:exit-code exit :stdout out :stderr err})))
    result))

(defn checkout-remote-commit [dir url commit]
  (when-not dir    (throw (Exception. "checkout-remote-commit called with no dir")))
  (when-not url    (throw (Exception. "checkout-remote-commit called with no url")))
  (when-not commit (throw (Exception. "checkout-remote-commit called with no commit")))
  ; from https://stackoverflow.com/questions/31278902
  ; and https://stackoverflow.com/questions/4565700
  (debug "git" "init" "--quiet" dir)
  (lsh "git" "init" "--quiet" dir)
  (debug "git" "remote" "add" "origin" url)
  (lsh "git" "remote" "add" "origin" url :dir dir)
  (debug "git" "fetch" "--depth" "1" "origin" commit)
  (lsh "git" "fetch" "--depth" "1" "origin" commit
       :env {"GIT_SSH_COMMAND" (str "ssh -i " SSH-KEY-FILENAME " -o IdentitiesOnly=yes -o GlobalKnownHostsFile=" SSH-KNOWN-HOSTS-FILENAME)}
       :dir dir)
  (debug "git" "checkout" "FETCH_HEAD")
  (lsh "git" "checkout" "FETCH_HEAD" :dir dir))

(defn deploy-to-fly [dir]
  (when-not dir (throw (Exception. "deploy-to-fly called with no dir")))
  (if DISABLE-DEPLOY
    (info "Skipping deployment since" (env-var-name "DISABLE_DEPLOY") "is set.")
    (do
      (debug "fly" "deploy" "--remote-only")
      (lsh "fly" "deploy" "--remote-only"
           :env {"FLY_API_TOKEN" FLY-DEPLOY-SECRET
                 "HOME" REMOTE-HOME}
           :dir dir))))

(defn execute-pre-deploy-steps [repo-dir]
  (let [repo-config (load-repo-config repo-dir)
        {secrets "secrets"
         volumes "volumes"
         certs   "certs"} repo-config]
    (when certs (warn "'certs' section not yet supported"))
    (when secrets (warn "'secrets' section not yet supported"))
    (when volumes (warn "'volumes' section not yet supported"))))

(defn build-commit [repo-url commit]
  (when-not repo-url (throw (Exception. "build-commit called with no repo-url")))
  (when-not commit   (throw (Exception. "build-commit called with no commit")))
  (fs/with-temp-dir [dir]
    (let [dir (str dir)]
      (checkout-remote-commit dir repo-url commit)
      (execute-pre-deploy-steps dir)
      (deploy-to-fly dir))))

(defn parse-gitea-webhook-body [body]
  (let [body (json/parse-string body)]
    {:repo-url (get-in body ["repository" (if GIT-USE-SSH "ssh_url" "clone_url")])
     :commit (get body "after")
     :ref (get body "ref")}))

(defn handle-request [req]
  (let [{:keys [repo-url commit ref]} (parse-gitea-webhook-body (:body req))
        event-type (get-in req [:headers "x-gitea-event-type"])
        ; TODO -- handle delivery IDs
        delivery-id (get-in req [:headers "x-gitea-delivery"])
        allowed-repo-url? (re-matches ALLOWED-REPO-RE repo-url)]
    (info "Processing webhook" {:repo-url repo-url :commit commit :delivery-id delivery-id :event event-type :ref ref})
    (if-not allowed-repo-url?
      (do
        (debug "Build skipped: repo URL not allowed" {:allowed-repos ALLOWED-REPO-RE :repo-url repo-url})
        {:status 400 :body "Sorry, I just can't work with you on this."})
      (if-not (= event-type "push")
        (do
          (debug "Build skipped: unsupported event type" {:expected "push" :received event-type})
          {:status 200 :body "Nothing to do for this event type."})
        (if-not (= ref MAIN-REF)
          (do
            (debug "Build skipped: non-deployable ref" {:deployable-ref MAIN-REF :this-ref ref})
            {:status 200 :body "Nothing to do for this ref."})
          (let [current @current-parallel-builds]
           (if (>= current MAX-PARALLEL-BUILDS)
            (do
              (debug "Build skipped: max parallel builds reached" {:current current :max MAX-PARALLEL-BUILDS})
              {:status 429 :body "Feeling a bit tired today, can we chat later?"})
            (do
              (swap! current-parallel-builds inc) ;; Fix this logic
              (try
                (build-commit repo-url commit)
                (info "Build succeeded!")
                {:status 200 :body "I did the thing!"}
                (catch Exception ex
                  (error ex "error building commit" {:repo-url repo-url :commit commit})
                  {:status 500 :body "Running into a bit of an issue here. Can we take this offline?"})
                (finally (swap! current-parallel-builds dec)))))))))))

(defn wrap-body-string [handler]
  (fn [req]
    (handler (assoc req :body (slurp (:body req))))))

; adapted from https://stackoverflow.com/questions/15443781/
; note -- the point here is to match the behavior of the hash_hmac() function in PHP
; so our signatures exactly match those generated by Gitea (https://docs.gitea.io/en-us/webhooks/)
(defn hmac [key string]
  (let [mac (javax.crypto.Mac/getInstance "HMACSHA256")
        secretKey (javax.crypto.spec.SecretKeySpec. (.getBytes key) (.getAlgorithm mac))]
    (apply str
           (map #(format "%02x" %)
                (-> (doto mac
                      (.init secretKey)
                      (.update (.getBytes string)))
                    .doFinal)))))

(defn wrap-is-authenticated [handler]
  (fn [req]
    (let [sig    (get-in req [:headers "x-gitea-signature"])
          body   (:body req)
          key    WEBHOOK-SECRET
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

(defn app []
  (wrap-request-logger
   (wrap-body-string
    (wrap-is-authenticated
     handle-request))))

;;
;; Startup
;;


(info "== gitea-fly-connector ==")
(info)
(info "Config settings:")
(info "           Server Port:" PORT)
(info "         Ref to deploy:" MAIN-REF)
(info "  Allowed repositories:" ALLOWED-REPO-RE)
(info "        Clone URL type:" (if GIT-USE-SSH "ssh" "https"))
(info "  Repo config filename:" REPO-CONFIG-FILE)
(info "   Max parallel builds:" MAX-PARALLEL-BUILDS)
(info "       Deploys enabled:" (not DISABLE-DEPLOY))
(info "             Log level:" LOG-LEVEL)
(info)
(info "Secrets:")
(info "         Fly API Token:" (if FLY-DEPLOY-SECRET "set" "NOT SET"))
(info "  Gitea Webhook secret:" (if WEBHOOK-SECRET "set" "NOT SET"))
(info "       SSH private key:" (if SSH-PRIVATE-KEY "set" "NOT SET"))
(info "       SSH fingerprint:" (or SSH-KEY-FINGERPRINT "NOT SET"))
(info "     SSH Allowed Hosts:" (or SSH-ALLOWED-HOSTS "NOT SET"))
(info)

(when SSH-PRIVATE-KEY
  (info "Writing SSH key to file:" SSH-KEY-FILENAME)
  (try
    (spit SSH-KEY-FILENAME SSH-PRIVATE-KEY)
    (lsh "chmod" "600" SSH-KEY-FILENAME)
    (catch Exception ex
      (error ex "could not write SSH key to file"))))

(when SSH-ALLOWED-HOSTS
  (info "Writing SSH Host key to file:" SSH-KNOWN-HOSTS-FILENAME)
  (try
    (spit SSH-KNOWN-HOSTS-FILENAME SSH-ALLOWED-HOSTS)
    (catch Exception ex
      (error ex "could not write host key to file"))))

(info "Listening on port" PORT)
(server/run-server (app) {:port PORT})

@(promise)