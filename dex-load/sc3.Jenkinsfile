pipeline {
    agent {
        label 'buildagent-matcher'
    }
    options {
        ansiColor('xterm')
        timeout(time: 45, unit: 'MINUTES')
    }
    parameters {
        string(name: 'SEED', defaultValue: 'test-seed', description: 'Seed prefix of generated accounts')
        string(name: 'AN', defaultValue: '6000', description: 'Count of generated accounts')
        string(name: 'AS', defaultValue: 'D', description: 'Chain ID')
    }
    environment {
        SBT_HOME = tool name: 'sbt-1.2.6', type: 'org.jvnet.hudson.plugins.SbtPluginBuilder$SbtInstallation'
        SBT_THREAD_NUMBER = "6"
        SBT_OPTS = '-Xmx10g -XX:ReservedCodeCacheSize=128m -XX:+CMSClassUnloadingEnabled'
        JAVA_OPTS ="-Xms6144m -Xmx6144m -XX:NewSize=256m -XX:MaxNewSize=356m -XX:PermSize=256m -XX:MaxPermSize=356m"
        PATH = "${env.SBT_HOME}/bin:${env.PATH}"
        SEED = "${SEED}"
        AN = "${AN}"
        AS = "${AS}"
        NODE = "${NODE}"
        MATCHER = "${MATCHER}"
        AIM = "${AIM}"
    }
    stages {
        stage('Cleanup') {
            steps {
                sh 'rm -rf ./dex-load/*.txt'
                sh 'git fetch --tags'
                sh 'find ~/.sbt/1.0/staging/*/waves -type d -name target | xargs -I{} rm -rf {} || true'
                sh 'find . -type d -name target | xargs -I{} rm -rf {} || true'
                sh 'sbt "cleanAll"'
            }
        }
        stage('Generate feeder file') {
            steps {
                sshagent (credentials: ['buildagent-matcher']) {
                    sh "scp buildagent-matcher@${LOADGEN}:/home/buildagent-matcher/key.txt ./dex-load"
                    sh "scp buildagent-matcher@${LOADGEN}:/home/buildagent-matcher/pairs.txt ./dex-load"
                }
                sh 'sbt "dex-load/generateFeeder"'
            }
        }
        stage("Web Socket") {
            steps {
                sh 'mv ./dex-load/feeder.csv ./dex-ws-load'
                sh 'cd ./dex-ws-load && sbt -J-Xms1056M -J-Xmx8192M -Dff=feeder.csv -Dws=ws://${AIM}:6886/ws/v0 -Drt=30 -Duc=6000 gatling:testOnly load.ConnectionsOnlyTest'
                script {
                    GRAFANA = sh(script: '''
                                            echo "https://${GRAFANA_URL}/d/WsyjIiHiz/system-metrics?orgId=5&var-hostname=${MATCHER_URL}&from=$(date -d '- 20 minutes' +'%s')000&to=$(date -d '+ 5 minutes' +'%s')000"
                                         ''', returnStdout: true)
                    currentBuild.description = "<a href='${GRAFANA}'>Grafana</a>"
                }
            }
        }
    }
    post {
        always {
            script {
                GRAFANA = sh(script: '''
                                    echo "https://${GRAFANA_URL}/d/WsyjIiHiz/system-metrics?orgId=5&var-hostname=${MATCHER_URL}&from=$(date -d '- 20 minutes' +'%s')000&to=$(date -d '+ 5 minutes' +'%s')000"
                                    ''', returnStdout: true)
                currentBuild.description = "<a href='${GRAFANA}'>Grafana</a>"
            }
        }
        cleanup {
            cleanWs()
        }
    }
}
