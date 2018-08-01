pipeline {
  agent any
  environment {
    BUILD_FILE='/var/lib/jenkins/clarks-buildInfo.txt'
    PREFIX='1.1'
    PROJECT='clarks'
    TAGS='deploy'
    REPO='CLARKS'
    BITBUCKET_URL='https://bitbucket.mrm-mccanntools.com'
  }
  options { timeout(time: 7, unit: 'DAYS') }
  stages {
    stage('Build') {
      when {
        expression { BRANCH_NAME =~ /(develop*|release.*|hotfix.*)/ }
      }
      steps {
        echo "download Hybris archive from AWS S3 ... "
        sh '''

        GIT_COMMIT=$(git rev-parse HEAD)

        OPT_CLARKS="./opt/clarks/"

        if [ ! -d ${OPT_CLARKS} ]; then
          mkdir -p ${OPT_CLARKS}
        else
          rm -rf ${OPT_CLARKS}/*
        fi
        curl -o "./opt/HYBRISCOMM6700P_3-80003492.ZIP" "https://hybris-accelerators.s3.amazonaws.com/HYBRISCOMM6700P_3-80003492.ZIP"

        echo "unpack Hybris platform ... "
        unzip -q "./opt/HYBRISCOMM6700P_3-80003492.ZIP" -d ${OPT_CLARKS} && rm "./opt/HYBRISCOMM6700P_3-80003492.ZIP"

        rsync -arP ./hybris/config-dev/ ./opt/clarks/hybris/config
        rsync -arP ./hybris/bin/ ./opt/clarks/hybris/bin
        export HYBRIS_PLATFORM_BIN=${PWD}/opt/clarks/hybris/bin/platform

        cd $HYBRIS_PLATFORM_BIN
        . ./setantenv.sh

        ant full

        '''

      }
      post {
        always{

          script {
            switch (currentBuild.currentResult) {
              case "SUCCESS":
                BITBUCKET_STATE = "SUCCESSFUL"
                break
              case "UNSTABLE":
                BITBUCKET_STATE = "FAILED"
                break
              case "FAILURE":
                BITBUCKET_STATE = "FAILED"
                break
              default:
                BITBUCKET_STATE = "FAILED"
                break
            }
          }


          withCredentials([string(credentialsId: 'Jenkins-CI-Token-Auth', variable: 'BITBUCKET_TOKEN')]) {

            sh """
            cat << EOF > ./build0.json
{ "state": \"${BITBUCKET_STATE}\", "key": "build", "name":"build", "url":\"${env.BUILD_URL}\", "description":\"${currentBuild.changeSets}\" }
EOF
            """
            sh '''
            set +x
            curl -H "Authorization: Bearer $BITBUCKET_TOKEN" -H "Content-Type: application/json" -X POST $BITBUCKET_URL/rest/build-status/1.0/commits/${GIT_COMMIT} -d @build0.json
            '''
          }

        }

        failure {
          emailext (
            subject: "BUILD FAILURE: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
            body:'''${SCRIPT, template="managed-groovy-html.template"}''',
            mimeType: 'text/html',
            to: ''
          )
        }
      }
    }
    stage('Unit Tests') {
      when {
        expression { BRANCH_NAME =~ /(develop*|release.*|hotfix.*)/ }
      }
      steps {
        echo "Run Unit Tests"
        sh '''
        set +x
        export HYBRIS_PLATFORM_BIN=${WORKSPACE}/opt/clarks/hybris/bin/platform
        export HYBRIS_LOG_DIR=${WORKSPACE}/opt/clarks/hybris/log
        export HYBRIS_BIN_CUSTOM=${WORKSPACE}/opt/clarks/hybris/bin/custom

        echo ${HYBRIS_PLATFORM_BIN}
        echo ${HYBRIS_LOG_DIR}
        echo ${HYBRIS_BIN_CUSTOM}

        cd ${HYBRIS_PLATFORM_BIN}

        echo "[RUN] ant updateMavenDependencies"
        ant updateMavenDependencies

        EXTENSIONS="$(find $HYBRIS_BIN_CUSTOM -maxdepth 2 -mindepth 2 -type d -exec basename {} \\; |xargs|sed 's/ /,/g')"
        echo "Extensions are: ${EXTENSIONS}"
        echo "[RUN] ant ci-ut -Dtestclasses.extensions=$EXTENSIONS "
        ant ci-ut -Dtestclasses.extensions="$EXTENSIONS"

        lines_percent=$(tidy -q -xml $HYBRIS_LOG_DIR/ci-ut/jacoco-report/index.html |grep -A2 Total|grep % |grep -oP \">\\K.*(?=</)\")

        '''

      }
      post {
        success {
          archiveArtifacts artifacts: 'opt/clarks/hybris/log/ci-ut/jacoco-report/*', fingerprint: true
          publishHTML([
            allowMissing: true,
            alwaysLinkToLastBuild: true,
            keepAll: true,
            reportDir: 'opt/clarks/hybris/log/ci-ut/jacoco-report',
            reportFiles: 'index.html',
            reportName: 'ci-ut-jacoco-report',
            reportTitles: 'jacoco-report'
          ])
        }
        always{

          script {
            switch (currentBuild.currentResult) {
              case "SUCCESS":
                BITBUCKET_STATE = "SUCCESSFUL"
                break
              case "UNSTABLE":
                BITBUCKET_STATE = "FAILED"
                break
              case "FAILURE":
                BITBUCKET_STATE = "FAILED"
                break
              default:
                BITBUCKET_STATE = "FAILED"
                break
            }
          }


          withCredentials([string(credentialsId: 'Jenkins-CI-Token-Auth', variable: 'BITBUCKET_TOKEN')]) {

            sh """
            cat << EOF > ./build-unit-tests.json
{ "state": \"${BITBUCKET_STATE}\", "key": "unit_tests", "name":"unit_tests", "url":\"${env.BUILD_URL}\", "description":\"${currentBuild.changeSets}\" }
EOF
            """
            sh '''
            set +x
            curl -H "Authorization: Bearer $BITBUCKET_TOKEN" -H "Content-Type: application/json" -X POST $BITBUCKET_URL/rest/build-status/1.0/commits/${GIT_COMMIT} -d @build-unit-tests.json
            '''
          }

        }

        failure {
          emailext (
            subject: "BUILD FAILURE: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
            body:'''${SCRIPT, template="managed-groovy-html.template"}''',
            mimeType: 'text/html',
            to: ''
          )
        }
      }
    }
    stage('Integration tests') {
      when {
        expression { BRANCH_NAME =~ /(develop*|release.*|hotfix.*)/ }
      }
      steps {
        echo "Run Integration Tests"
        sh '''
        set +x
        export HYBRIS_PLATFORM_BIN=${WORKSPACE}/opt/clarks/hybris/bin/platform
        export HYBRIS_LOG_DIR=${WORKSPACE}/opt/clarks/hybris/log
        export HYBRIS_BIN_CUSTOM=${WORKSPACE}/opt/clarks/hybris/bin/custom

        echo ${HYBRIS_PLATFORM_BIN}
        echo ${HYBRIS_LOG_DIR}
        echo ${HYBRIS_BIN_CUSTOM}

        cd ${HYBRIS_PLATFORM_BIN}

        EXTENSIONS="$(find $HYBRIS_BIN_CUSTOM -maxdepth 2 -mindepth 2 -type d -exec basename {} \\; |xargs|sed 's/ /,/g')"
        echo "Extensions are: ${EXTENSIONS}"
        echo "[RUN] ant ci-it -Dtestclasses.extensions=$EXTENSIONS "
        ant ci-it -Dtestclasses.extensions="$EXTENSIONS"

        lines_percent=$(tidy -q -xml  $HYBRIS_LOG_DIR/ci-it/jacoco-report/index.html |grep -A2 Total|grep % |grep -oP \">\\K.*(?=</)\")

        '''


      }
      post {
        success{
          archiveArtifacts artifacts: 'opt/clarks/hybris/log/ci-it/jacoco-report/*', fingerprint: true
          publishHTML([
            allowMissing: true,
            alwaysLinkToLastBuild: true,
            keepAll: true,
            reportDir: 'opt/clarks/hybris/log/ci-it/jacoco-report',
            reportFiles: 'index.html',
            reportName: 'ci-it-jacoco-report',
            reportTitles: 'jacoco-report'
          ])
        }
        always{

          script {
            switch (currentBuild.currentResult) {
              case "SUCCESS":
                BITBUCKET_STATE = "SUCCESSFUL"
                break
              case "UNSTABLE":
                BITBUCKET_STATE = "FAILED"
                break
              case "FAILURE":
                BITBUCKET_STATE = "FAILED"
                break
              default:
                BITBUCKET_STATE = "FAILED"
                break
            }
          }


          withCredentials([string(credentialsId: 'Jenkins-CI-Token-Auth', variable: 'BITBUCKET_TOKEN')]) {

            sh """
            cat << EOF > ./build-unit-tests.json
{ "state": \"${BITBUCKET_STATE}\", "key": "int_tests", "name":"int_tests", "url":\"${env.BUILD_URL}\", "description":\"${currentBuild.changeSets}\" }
EOF
            """
            sh '''
            set +x
            curl -H "Authorization: Bearer $BITBUCKET_TOKEN" -H "Content-Type: application/json" -X POST $BITBUCKET_URL/rest/build-status/1.0/commits/${GIT_COMMIT} -d @build-unit-tests.json
            '''
          }

        }

        failure {
          emailext (
            subject: "BUILD FAILURE: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
            body:'''${SCRIPT, template="managed-groovy-html.template"}''',
            mimeType: 'text/html',
            to: ''
          )
        }
      }
    }
  }
}
