(ns npm-force-resolutions.core-test
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.test :refer-macros [async deftest is testing run-tests]]
            [cljs.core.async :refer [<! >!]]
            [cljs-http.client :as http]
            [xmlhttprequest :refer [XMLHttpRequest]]
            [npm-force-resolutions.core :refer [main node-slurp read-json find-resolutions
                                                patch-all-dependencies update-on-requires
                                                add-dependencies update-package-lock
                                                fix-existing-dependency fetch-resolved-resolution]]))

(set! js/XMLHttpRequest XMLHttpRequest)

(deftest test-read-file
  (let [package-lock-file (node-slurp "./src/fixtures/boom_hoek/package-lock.json")]
    (is (re-find #"package-lock-fixture-before" package-lock-file))))

(deftest test-read-package-lock-json
  (let [package-lock (read-json "./src/fixtures/boom_hoek/package-lock.json")]
    (is (= (get package-lock "name") "package-lock-fixture-before"))))

(deftest test-fetch-resolved-resolution
  (async done
    (go
      (let [resolution (<! (fetch-resolved-resolution "hoek" "4.2.1"))]
        (is (= resolution
              {"hoek"
                {"integrity" "sha512-QLg82fGkfnJ/4iy1xZ81/9SIJiq1NGFUMGs6ParyjBZr6jW2Ufj/snDqTHixNlHdPNwN2RLVD0Pi3igeK9+JfA=="
                 "version" "4.2.1"
                 "resolved" "https://registry.npmjs.org/hoek/-/hoek-4.2.1.tgz"}}))
        (done)))))

(def hoek-resolution
  {"integrity" "sha512-QLg82fGkfnJ/4iy1xZ81/9SIJiq1NGFUMGs6ParyjBZr6jW2Ufj/snDqTHixNlHdPNwN2RLVD0Pi3igeK9+JfA=="
    "version" "4.2.1"
    "resolved" "https://registry.npmjs.org/hoek/-/hoek-4.2.1.tgz"})

(def boom-hoek-resolutions
  {"hoek" hoek-resolution
   "webpack"
    {"integrity" "sha512-RC6dwDuRxiU75F8XC4H08NtzUrMfufw5LDnO8dTtaKU2+fszEdySCgZhNwSBBn516iNaJbQI7T7OPHIgCwcJmg=="
      "version" "5.23.0"
      "resolved" "https://registry.npmjs.org/webpack/-/webpack-5.23.0.tgz"}})

(deftest test-find-resolutions
  (async done
    (go
      (let [resolutions (<! (find-resolutions "./src/fixtures/boom_hoek"))]
        (is (= resolutions boom-hoek-resolutions))
        (done)))))

(deftest test-updates-from-requires
  (let [dependency {"requires" {"hoek" "1.0.0"}}
        updated-dependency (update-on-requires boom-hoek-resolutions dependency)]
    (is (= updated-dependency
          {"requires" {"hoek" "4.2.1"}}))))

(deftest test-updates-requires
  (let [package-lock (read-json "./src/fixtures/boom_hoek/package-lock.json")
        updated-package-lock (patch-all-dependencies boom-hoek-resolutions package-lock)]
    (is (= {"hoek" "4.2.1"}
          (-> updated-package-lock
            (get "dependencies")
            (get "boom")
            (get "requires"))))))

(deftest test-updates-requires-recursivelly
  (let [package-lock (read-json "./src/fixtures/boom_hoek/package-lock.json")
        updated-package-lock (patch-all-dependencies boom-hoek-resolutions package-lock)]
    (is (= {"hoek" "4.2.1"}
          (-> updated-package-lock
            (get "dependencies")
            (get "fsevents")
            (get "dependencies")
            (get "boom")
            (get "requires"))))))

(deftest test-add-dependencies-if-there-is-require
  (let [dependency {"requires" {"hoek" "1.0.0"}
                    "dependencies" {"foo" {"version" "2.0.0"}}}
        updated-dependency (add-dependencies boom-hoek-resolutions dependency)]
    (is (= updated-dependency
          {"requires" {"hoek" "1.0.0"}
           "dependencies" {"foo" {"version" "2.0.0"}
                           "hoek" hoek-resolution}}))))

(deftest test-add-dependencies-if-there-is-require-and-no-dependencies
  (let [dependency {"requires" {"hoek" "1.0.0"}}
        updated-dependency (add-dependencies boom-hoek-resolutions dependency)]
    (is (= updated-dependency
          {"requires" {"hoek" "1.0.0"}
           "dependencies" {"hoek" hoek-resolution}}))))

(deftest test-do-not-add-dependencies-if-there-is-no-require
  (let [dependency {"requires" {}
                    "dependencies" {"foo" {"version" "2.0.0"}}}
        updated-dependency (add-dependencies boom-hoek-resolutions dependency)]
    (is (= updated-dependency
          {"requires" {}
           "dependencies" {"foo" {"version" "2.0.0"}}}))))

(deftest test-add-dependencies-recursivelly
  (let [package-lock (read-json "./src/fixtures/boom_hoek/package-lock.json")
        updated-package-lock (patch-all-dependencies boom-hoek-resolutions package-lock)]
    (is (= {"hoek" hoek-resolution}
          (-> updated-package-lock
            (get "dependencies")
            (get "fsevents")
            (get "dependencies")
            (get "boom")
            (get "dependencies"))))))

(deftest test-fix-existing-dependency
  (let [dependency {"version" "2.16.3"
                    "resolved" "https://registry.npmjs.org/hoek/-/hoek-2.16.3.tgz"
                    "integrity" "sha1-ILt0A9POo5jpHcRxCo/xuCdKJe0="
                    "dev" true}
        updated-dependency (fix-existing-dependency boom-hoek-resolutions "hoek" dependency)]
    (is (= updated-dependency
          (merge hoek-resolution {"dev" true})))))

(deftest test-does-not-fix-existing-dependency-that-is-not-on-resolutions
  (let [dependency {"version" "2.16.3"
                    "resolved" "https://registry.npmjs.org/hoek/-/hoek-2.16.3.tgz"
                    "integrity" "sha1-ILt0A9POo5jpHcRxCo/xuCdKJe0="
                    "dev" true}
        updated-dependency (fix-existing-dependency boom-hoek-resolutions "foo" dependency)]
    (is (= updated-dependency
          dependency))))

(deftest test-update-package-lock
  (async done
    (go
      (let [expected-package-lock (read-json "./src/fixtures/boom_hoek/package-lock.after.json")
            updated-package-lock (<! (update-package-lock "./src/fixtures/boom_hoek"))]
        (is (= (get expected-package-lock "dependencies")
              (get updated-package-lock "dependencies")))
        (done)))))

(deftest test-update-package-lock-with-require
  (async done
    (go
      (let [updated-package-lock (<! (update-package-lock "./src/fixtures/sfdx-cli_axios"))]
        (is (=
              {"version" "0.19.2"
              "resolved" "https://registry.npmjs.org/axios/-/axios-0.19.2.tgz"
              "integrity" "sha512-fjgm5MvRHLhx+osE2xoekY70AhARk3a6hkN+3Io1jc00jtquGvxYlKlsFUhmUET0V5te6CcZI7lcv2Ym61mjHA=="
              "requires" {
                "follow-redirects" "\\^1.10.0"
              }}
              (-> updated-package-lock
                (get "dependencies")
                (get "@salesforce/telemetry")
                (get "dependencies")
                (get "axios"))))
        (done)))))

(enable-console-print!)
(run-tests)