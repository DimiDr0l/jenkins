#!groovy

library(
    identifier: 'shared_lib@master',
    changelog: false,
    retriever: modernSCM(
        scm: [
            $class: 'GitSCMSource',
            remote: 'placeholder_git_lib_repo',
            credentialsId: 'credentialsid',
        ],
    )
)
getGlobalEnv()

SETTINGS_REPO = params.SETTINGS_REPO
SETTINGS_BRANCH = params.SETTINGS_BRANCH
KIBCHAOS_BRANCH = params.KIBCHAOS_BRANCH
TEST_RUN_ID = params.TEST_RUN_ID
PROJECT_SETTINGS_ID = params.PROJECT_SETTINGS_ID
DEBUG = params.DEBUG
DRY_RUN = params.DRY_RUN
MULTI_TEST_RUNS = params.MULTI_TEST_RUNS
ANSIBLE_VERBOSE = "${DEBUG ? ' -vvv ' : ''}"

properties([
    parameters([
        string(
            name: 'SETTINGS_REPO',
            defaultValue: 'placeholder_git_repo/project-settings.git',
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
            name: 'KIBCHAOS_BRANCH',
            defaultValue: 'master',
            trim: true,
            description: 'Ветка репы kibchaos'
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
        booleanParam(
            name: 'DEBUG',
            defaultValue: false,
        ),
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: false,
        ),
        booleanParam(
            name: 'MULTI_TEST_RUNS',
            defaultValue: false,
        ),
    ])
])

pipeline {
    agent {
        label 'dind'
    }
    triggers {
        GenericTrigger(
            genericRequestVariables: [
                [key: 'SETTINGS_REPO'],
                [key: 'SETTINGS_BRANCH'],
                [key: 'KIBCHAOS_BRANCH'],
                [key: 'TEST_RUN_ID'],
                [key: 'PROJECT_SETTINGS_ID'],
                [key: 'DEBUG'],
                [key: 'DRY_RUN'],
                [key: 'MULTI_TEST_RUNS']
            ],
            causeString: 'Triggered on $TEST_RUN_ID',
            tokenCredentialId: 'devbuildTriggerToken',
            printContributedVariables: false,
            printPostContent: true,
            silentResponse: false,
        )
    }
    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '60', artifactNumToKeepStr: '60'))
        timestamps()
        timeout(time: 24, unit: 'HOURS')
        ansiColor('xterm')
    }

    environment {
        PROJECT_SETTINGS_DIR = "${WORKSPACE}/settings"
        PROJECT_DIR = "${PROJECT_SETTINGS_DIR}/${PROJECT_SETTINGS_ID}"
        CONFIG_PATH = "${PROJECT_DIR}/config.yml"
        INVENTORY = "${PROJECT_DIR}/inventory"
        VAULT_FILE = "${PROJECT_DIR}/vault.yml"
        VAULT_PASSWORD = "${PROJECT_DIR}/vault-password-file"
        VAULT_PASS_CREDS = 'kibchaos-vault'
        PRIVATE_KEY = "${PROJECT_DIR}/private_key"
        PRIVATE_KEY_CREDS = 'chaos_key'
        ORDER = "${PROJECT_DIR}/.order"

        ANSIBLE_CONFIG = "${WORKSPACE}/ansible/ansible.cfg"

        PLAY_EXTRAS = \
            " -e @${CONFIG_PATH} " +
            " -e @${VAULT_FILE} " +
            " -e testrun_id=${TEST_RUN_ID} " +
            " --vault-password-file ${VAULT_PASSWORD} " +
            " ${ANSIBLE_VERBOSE}"
    }

    stages {
        stage('Env') {
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    startStage()
                    wrap([$class: 'BuildUser']) {
                        if (env.BUILD_USER) {
                            aborted()
                        }
                    }
                    if (!SETTINGS_REPO || !SETTINGS_BRANCH || !TEST_RUN_ID || !PROJECT_SETTINGS_ID || !KIBCHAOS_BRANCH) {
                        log.fatal(
                            'Не задан один из параметров:\n' +
                            "SETTINGS_REPO: ${SETTINGS_REPO}\n" +
                            "SETTINGS_BRANCH: ${SETTINGS_BRANCH}\n" +
                            "KIBCHAOS_BRANCH: ${KIBCHAOS_BRANCH}\n" +
                            "TEST_RUN_ID: ${TEST_RUN_ID}\n" +
                            "PROJECT_SETTINGS_ID: ${PROJECT_SETTINGS_ID}\n" +
                            "DRY_RUN: ${DRY_RUN}\n" +
                            "MULTI_TEST_RUNS: ${MULTI_TEST_RUNS}\n"
                        )
                    }
                    finishStage()
                }
            }
        }

        stage('Checkout') {
            when {
                expression {
                    currentBuild.currentResult == 'SUCCESS'
                }
            }
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    startStage()
                    git.repoCheckout(
                        gitUrl: 'placeholder_git_repo/kibchaos.git',
                        branch: KIBCHAOS_BRANCH,
                    )
                    git.repoCheckout(
                        gitUrl: SETTINGS_REPO,
                        targetDir: env.PROJECT_SETTINGS_DIR,
                        branch: SETTINGS_BRANCH,
                    )
                    finishStage()
                }
            }
        }

        stage('Start test run') {
            when {
                expression {
                    currentBuild.currentResult == 'SUCCESS'
                }
            }
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    startStage()
                    // Get the TestRun status
                    Object testRun = tms.getTestRunById(params.TEST_RUN_ID)
                    // Get the project info
                    Object project = tms.getProjectById(testRun.projectId)
                    String projectFromConfig = readYaml(file: "${env.PROJECT_DIR}/config.yml").tms_project_name

                    // Validate webhook configuration
                    if (project.name != projectFromConfig) {
                        log.fatal 'tms_project_name in settings does not match project name in testit'
                    }
                    // Checking TestRun launch method
                    if (!testRun.testPlanId) {
                        log.fatal 'Wrong way to TestRun start. Only launch from TestPlan supported'
                    }

                    if (!params.MULTI_TEST_RUNS && tms.getTestRunsByProjectId(testRun.projectId)) {
                        List testResults = []
                        testRun.testResults.each { test ->
                            testResults += [
                                configurationId: test.configuration.id,
                                autoTestExternalId: test.autoTest.externalId,
                                outcome: 'Skipped',
                                message: 'Skipped because another TestRun is already running',
                            ]
                        }
                        // Set tests of the testrun status to Skipped
                        log.info 'Set tests of the testrun status to Skipped'
                        tms.setResultToTestRuns(params.TEST_RUN_ID, testResults)
                        aborted()
                    }
                    else if (testRun.stateName.equalsIgnoreCase('NotStarted')) {
                        // Start the TestRun
                        log.info 'Start the TestRun'
                        tms.actionTestRunById(params.TEST_RUN_ID, 'start')
                    }
                    finishStage()
                }
            }
        }

        stage('Get tasks') {
            when {
                expression {
                    currentBuild.currentResult == 'SUCCESS'
                }
            }
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    startStage()
                    Map priorityMap = [
                        'Highest': 1,
                        'High': 2,
                        'Medium': 3,
                        'Low': 4,
                        'Lowest': 5,
                    ]
                    // Get the TestRun status
                    Object testRun = tms.getTestRunById(params.TEST_RUN_ID)
                    List testProjects = []

                    if (testRun.stateName.equalsIgnoreCase('Stopped')) {
                        aborted()
                    }
                    else {
                        Map testNameConfigs = [:]
                        testRun.testResults*.configuration.name
                        .unique()
                        .each { name ->
                            testRun.testResults.findAll { test ->
                                test.configuration.name == name
                            }
                            .findResults { test ->
                                testNameConfigs[name] = test.configuration.capabilities.Priority
                            }
                        }

                        testNameConfigs.each { nameConfig ->
                            List tests = []
                            testRun.testResults.findAll { test ->
                                test.configuration.name == nameConfig.key
                            }.findResults { test ->
                                String priority = tms.getWorkItemById(test.testPoint.workItemId, test.workItemVersionId).priority
                                tests += [
                                    name: test.autoTest?.labels[0].name,
                                    namespace: test.autoTest.namespace,
                                    priority: priorityMap[priority],
                                ]
                            }
                            testProjects += [
                                name: nameConfig.key,
                                priority: nameConfig.value,
                                autotests: tests
                            ]
                        }

                        String contextOrderFile = ''
                        testProjects.each { project ->
                            String tags = project.autotests*.name.join(' ')
                            contextOrderFile += project.name + '=' + tags + '\n'
                        }
                        writeJSON(json: testProjects, file: "${env.PROJECT_DIR}/.order.json")
                        writeFile(text: contextOrderFile, file: env.ORDER)

                        log.info '\n' + contextOrderFile
                        // log.info '\n' + writeJSON(json: testProjects, returnText: true)
                    }
                    finishStage()
                }
            }
        }

        stage('Pre-check') {
            when {
                expression {
                    currentBuild.currentResult == 'SUCCESS'
                }
            }
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    startStage()
                    ansibleExecute(
                        command: \
                            'ansible-playbook ansible/tms_pre_check.yml -i localhost, ' +
                            " -e order_dir=${env.PROJECT_DIR} " +
                            env.PLAY_EXTRAS,
                    )
                    finishStage()
                }
            }
        }

        stage('Run tests') {
            when {
                expression {
                    currentBuild.currentResult == 'SUCCESS'
                }
            }
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    startStage()
                    try {
                        ansibleExecute(
                            command: './bin/run.sh'
                        )
                    } catch (err) {
                        if ("${err}".split(' ').last() == '5') {
                            aborted()
                        } else {
                            log.fatal 'Errors occurred during tests execution'
                        }
                    }
                    finishStage()
                }
            }
        }
    }

    post {
        always {
            script {
                if (fileExists("${env.PROJECT_DIR}/vs*.yml")) {
                    archiveArtifacts(
                        artifacts: "${env.PROJECT_DIR}/vs*.yml",
                        allowEmptyArchive: false
                    )
                }
                String testRunStatus = tms.getTestRunById(params.TEST_RUN_ID).stateName
                if (testRunStatus in ['InProgress', 'NotStarted']) {
                    tms.actionTestRunById(params.TEST_RUN_ID, 'stop')
                    log.info 'Test run is Stoped'
                }
            }
        }

        cleanup {
            cleanWs()
            script {
                startStage('test cleanup')
                String extras = env.PLAY_EXTRAS
                if (fileExists("${env.PROJECT_DIR}/.order.json")) {
                    Object order = readJSON(
                        file: "${env.PROJECT_DIR}/.order.json"
                    )
                    Object groups = order.name.join(',')
                    extras = env.PLAY_EXTRAS + ' -l ' + groups
                    ansibleExecute(
                        command: \
                            'ansible-playbook ansible/test_cleanup.yml ' +
                            " -i ${env.PROJECT_DIR}/inventory " +
                            ' -e is_testrun=True ' +
                            " -e order_dir=${env.PROJECT_DIR} " +
                            " -e ansible_ssh_private_key_file=${env.PRIVATE_KEY} " +
                            extras,
                    )
                }
                finishStage('test cleanup')
            }
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
