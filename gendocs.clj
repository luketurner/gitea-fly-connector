 (ns gendocs
   (:require [rewrite-clj.zip :as z]
             [clojure.string :as string]))

(def zloc (z/of-file "gfc.clj"))

(defn read-readme [] (slurp "README.md"))
(defn write-readme [v] (spit "README.md" v))

(defn find-next-defenv
  [zloc]
  (z/find-next zloc (fn [p] (= (z/sexpr (z/next p)) 'defenv))))

(defn get-all-envs []
  (loop [zloc (find-next-defenv zloc) all-envs []]
    (if-not zloc
      all-envs
      (let [[_ name docstring & {:keys [parse-fn default]}] (z/sexpr zloc)
            env {:name (str "GFC_" name)
                 :docstring docstring
                 :parse-fn parse-fn
                 :default default}]
        (recur (find-next-defenv zloc) (conj all-envs env))))))

(defn markdown-for-envs
  []
  (apply str "\n\n"
         (for [{:keys [name docstring parse-fn default]} (get-all-envs)]
           (str
            "**" name "** "
            (if (or parse-fn (some? default))
              (str "("
                   (if parse-fn (str "parser: " parse-fn (if (some? default) "; " "")) "")
                   (if (some? default) (str "default: " default "") "")
                   ")")
              "")
            "<br>" docstring "\n\n"))))

(defn replace-section
  [text section replacement-text]
  (string/replace text
                  (re-pattern (str "(?ms)<!-- gendocs section " section " -->.*<!-- gendocs section end -->"))
                  (string/re-quote-replacement (str "<!-- gendocs section " section " -->" replacement-text "<!-- gendocs section end -->"))))

(-> (read-readme)
    (replace-section "env" (markdown-for-envs))
    (write-readme))