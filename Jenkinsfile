def version = env.BRANCH_NAME

pipeline {
    agent none

    stages {
        stage("Review") {
            when {
                expression { env.CHANGE_ID != null }
            }
            steps {
                node("slave-sbt") {
                    checkout scm
                    sh 'sbt clean scalafmtCheck scalafmtSbtCheck paradox'
                }
            }
        }
        stage("Build Image") {
            when {
                expression { env.CHANGE_ID == null && version ==~ /v\d+\.\d+\.\d+.*/ }
            }
            steps {
                node("slave-sbt") {
                    sh "sbt clean paradox universal:packageZipTarball"
                    sh "mv target/universal/docs.tgz ."
                    sh "oc start-build docs-build --from-file=docs.tgz --follow"
                    openshiftTag srcStream: 'docs', srcTag: 'latest', destStream: 'docs', destTag: version.substring(1), verbose: 'false'
                }
            }
        }
    }
}
