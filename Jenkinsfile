String version = env.BRANCH_NAME
Boolean isRelease = version ==~ /v\d+\.\d+\.\d+.*/
Boolean isPR = env.CHANGE_ID != null

pipeline {
    agent none

    stages {
        stage("Review") {
            when {
                expression { isPR }
            }
            steps {
                node("slave-sbt") {
                    checkout scm
                    sh 'sbt clean scalafmtCheck scalafmtSbtCheck paradox'
                }
            }
        }
        stage("Deploy GhPages") {
            when {
                expression { !isPR && !isRelease }
            }
            steps {
                node("slave-sbt") {
                    checkout scm
                    sh "sbt clean makeSite ghpagesPushSite"
                }
            }
        }
    }
}
