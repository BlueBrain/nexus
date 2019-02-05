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
        stage("Deploy GitHub Pages") {
            when {
                expression { version == "master" }
            }
            steps {
                node("slave-sbt") {
                    sshagent(['bbpnexusbuildbot-ssh-key']) {
                        sh 'rm -rf ~/.sbt/ghpages/'
                        sh 'rm -rf nexus && git clone git@github.com:BlueBrain/nexus.git'
                        sh 'cd nexus && git config user.email "noreply@epfl.ch" && git config user.name "BBP Nexus Build Bot"'
                        sh 'cd nexus && sbt clean makeSite ghpagesPushSite'
                    }
                }
            }
        }
    }
}
