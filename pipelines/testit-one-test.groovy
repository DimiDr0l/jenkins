#!groovy

String bbCreds = env.JENKINS_URL =~ /(?i)qa-jenkins.ru/ ? '0fd7f3e0-957e-4e3a-8e3b-b383d7af9d8a' : 'git_creds'

library(
    identifier: 'shared_lib@master',
    changelog: false,
    retriever: modernSCM(
        scm: [
            $class: 'GitSCMSource',
            remote: 'https://github.com/DimiDr0l/jenkins.git',
            credentialsId: bbCreds,
        ],
    )
)

getGlobalEnv()

properties([
    parameters([
        string(
            name: 'SETTINGS_REPO',
            defaultValue: 'https://github.com/DimiDr0l/project-settings.git',
            trim: true,
            description: 'Репозиторий конфигов'
        ),
        string(
            name: 'SETTINGS_BRANCH',
            defaultValue: 'master',
            trim: true,
            description: 'Ветка конфигов'
        ),
        string(
            name: 'ANSIBLE_BRANCH',
            defaultValue: 'master',
            trim: true,
            description: 'Ветка репы'
        ),
        string(
            name: 'TEST_RUN_ID',
            defaultValue: '',
            trim: true,
            description: ''
        ),
        string(
            name: 'PROJECT_SETTINGS_ID',
            defaultValue: '',
            trim: true,
            description: 'Название папки проекта'
        ),
        string(
            name: 'INVENTORY_GROUP',
            defaultValue: '',
            trim: true,
        ),
        string(
            name: 'TEST_NAME',
            defaultValue: '',
            trim: true,
        ),
        booleanParam(
            name: 'DEBUG',
            defaultValue: false,
        ),
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: false,
        ),
    ])
])

pipeline {
    agent {
        kubernetes(k8sagent())
    }

    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '100', artifactNumToKeepStr: '100'))
        timeout(time: 24, unit: 'HOURS')
        ansiColor('xterm')
        timestamps()
    }

    environment {
        PROJECT_SETTINGS_DIR = "${WORKSPACE}/settings"
        PROJECT_DIR = "${PROJECT_SETTINGS_DIR}/${params.PROJECT_SETTINGS_ID}"
        CONFIG_PATH = "${PROJECT_DIR}/config.yml"
        INVENTORY = "${PROJECT_DIR}/inventory"
        VAULT_FILE = "${PROJECT_DIR}/vault.yml"
        VAULT_PASSWORD = "${PROJECT_DIR}/vault-password-file"
        PRIVATE_KEY = "${PROJECT_DIR}/private_key"

        ANSIBLE_DIR = "${WORKSPACE}/ansible"
        ANSIBLE_CONFIG = "${ANSIBLE_DIR}/ansible.cfg"

        TEST_LOG = "${WORKSPACE}/${params.TEST_NAME}_${params.INVENTORY_GROUP}.log"

        PLAY_EXTRAS = \
            " -e @${CONFIG_PATH} " +
            " -l ${params.INVENTORY_GROUP} " +
            " -e test_log=${TEST_LOG} " +
            " -e autotest=${params.TEST_NAME} " +
            " -e output_dir=${WORKSPACE} " +
            " -e ansible_ssh_private_key_file=${PRIVATE_KEY} " +
            " -e testrun_id=${params.TEST_RUN_ID} " +
            " -i ${INVENTORY} " +
            " -e @${VAULT_FILE} " +
            " --vault-password-file ${VAULT_PASSWORD} " +
            "${params.DEBUG ? ' -vvv ' : ''}"
    }

    stages {
        stage('Env') {
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    startStage()
                    // wrap([$class: 'BuildUser']) {
                    //     if (env.BUILD_USER) {
                    //         aborted()
                    //     }
                    // }
                    finishStage()
                }
            }
        }

        stage('Checkout') {
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    startStage()
                    git.repoCheckout(
                        gitUrl: 'https://github.com/DimiDr0l/ansible.git',
                        branch: params.ANSIBLE_BRANCH,
                    )
                    git.repoCheckout(
                        gitUrl: params.SETTINGS_REPO,
                        targetDir: env.PROJECT_SETTINGS_DIR,
                        branch: params.SETTINGS_BRANCH,
                    )
                    finishStage()
                }
            }
        }

        stage('Run test') {
            steps {
                script {
                    startStage()
                    String startTimeTest = sysUtils.shStdout('date +%s%3N')
                    String outcome = 'Blocked'
                    String message = "Test_${params.TEST_NAME}_passed"
                    String cmdRunTest = "ansible-playbook ${env.ANSIBLE_DIR}/${params.TEST_NAME}.yml ${env.PLAY_EXTRAS} | tee ${env.TEST_LOG}"

                    if (params.DRY_RUN) {
                        log.warning "DRY RUN: ${cmdRunTest}"
                    }
                    else {
                        stage("${params.TEST_NAME} test run") {
                            startStage()
                            try {
                                ansibleExecute(
                                    command: cmdRunTest
                                )
                            }
                            catch (err) {
                                outcome = 'Skipped'
                                message = 'Skipped_by_test_fail'
                            }
                            finishStage()
                        }

                        stage('Send results to Test IT') {
                            startStage()
                            String paramsSendResult = \
                                "${env.PLAY_EXTRAS}" +
                                " -e configuration=${params.INVENTORY_GROUP} " +
                                " -e BUILD_URL=${env.BUILD_URL} " +
                                " -e test_start_time=${startTimeTest}" +
                                " -e outcome=${outcome} " +
                                " -e message=${message} "
                            ansibleExecute(
                                command: "ansible-playbook ${env.ANSIBLE_DIR}/tms_send_result.yml ${paramsSendResult}"
                            )
                            finishStage()
                        }
                    }
                    finishStage()
                }
            }
        }
    }

    post {
        cleanup {
            cleanWs()
        }
    }
}

void aborted(String msg = env.STAGE_NAME) {
    currentBuild.result = 'ABORTED'
    log.warning currentBuild.result + ': "' + msg + '"'
}

void startStage(String msg = env.STAGE_NAME) {
    log.info 'STARTED STAGE: "' + msg + '"'
}

void finishStage(String msg = env.STAGE_NAME) {
    log.info 'FINISH STAGE: "' + msg + '"'
}
