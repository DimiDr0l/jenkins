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

List testProjects = []
String testRunUrl = ''
SETTINGS_REPO = params.SETTINGS_REPO
SETTINGS_BRANCH = params.SETTINGS_BRANCH
ANSIBLE_BRANCH = params.ANSIBLE_BRANCH
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
        kubernetes(k8sagent())
    }

    triggers {
        GenericTrigger(
            genericRequestVariables: [
                [key: 'SETTINGS_REPO'],
                [key: 'SETTINGS_BRANCH'],
                [key: 'ANSIBLE_BRANCH'],
                [key: 'TEST_RUN_ID'],
                [key: 'PROJECT_SETTINGS_ID'],
                [key: 'DEBUG'],
                [key: 'DRY_RUN'],
                [key: 'MULTI_TEST_RUNS'],
                [key: 'CONTAINER_LIMITS_MEMORY'],
                [key: 'CONTAINER_LIMITS_CPU']
            ],
            causeString: 'Triggered on $TEST_RUN_ID',
            tokenCredentialId: 'devbuildTriggerToken',
            printContributedVariables: true,
            printPostContent: true,
            silentResponse: false,
        )
    }
    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '60', artifactNumToKeepStr: '60'))
        timeout(time: 24, unit: 'HOURS')
        ansiColor('xterm')
        timestamps()
    }

    environment {
        PROJECT_SETTINGS_DIR = "${WORKSPACE}/settings"
        PROJECT_DIR = "${PROJECT_SETTINGS_DIR}/${PROJECT_SETTINGS_ID}"
        CONFIG_PATH = "${PROJECT_DIR}/config.yml"
        INVENTORY = "${PROJECT_DIR}/inventory"
        VAULT_FILE = "${PROJECT_DIR}/vault.yml"
        VAULT_PASSWORD = "${PROJECT_DIR}/vault-password-file"
        PRIVATE_KEY = "${PROJECT_DIR}/private_key"

        ANSIBLE_DIR = "${WORKSPACE}/ansible"
        ANSIBLE_CONFIG = "${ANSIBLE_DIR}/ansible.cfg"

        TMS_API_URL = "${env.TMS_URL}/api/v2"

        PLAY_EXTRAS = \
            " -e @${CONFIG_PATH} " +
            " -e output_dir=${WORKSPACE} " +
            " -e ansible_ssh_private_key_file=${PRIVATE_KEY} " +
            " -e testrun_id=${TEST_RUN_ID} " +
            " -i ${INVENTORY} " +
            " -e @${VAULT_FILE} " +
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
                    if (!SETTINGS_REPO || !SETTINGS_BRANCH || !TEST_RUN_ID || !PROJECT_SETTINGS_ID || !ANSIBLE_BRANCH) {
                        log.fatal(
                            'Не задан один из параметров:\n' +
                            "SETTINGS_REPO: ${SETTINGS_REPO}\n" +
                            "SETTINGS_BRANCH: ${SETTINGS_BRANCH}\n" +
                            "ANSIBLE_BRANCH: ${ANSIBLE_BRANCH}\n" +
                            "TEST_RUN_ID: ${TEST_RUN_ID}\n" +
                            "PROJECT_SETTINGS_ID: ${PROJECT_SETTINGS_ID}\n"
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
                        gitUrl: 'https://github.com/DimiDr0l/ansible.git',
                        branch: ANSIBLE_BRANCH,
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
                    // hack test_execution_time & time_between_tests
                    if (!params.CRON_RUN && params.PROJECT_SETTINGS_ID == 'project-id' && sysUtils.getBuildCauses() != 'generic') {
                        log.debug 'Hack: test_execution_time=120 & time_between_tests=30'
                        Object aMapconfig = readYaml(file: "${env.PROJECT_DIR}/config.yml")
                        aMapconfig.test_execution_time = 120
                        aMapconfig.time_between_tests = 30
                        writeYaml(file: "${env.PROJECT_DIR}/config.yml", data: aMapconfig, overwrite: true)
                    }

                    // Get the TestRun status
                    Object testRun = tms.getTestRunById(TEST_RUN_ID)
                    // Get the project info
                    Object project = tms.getProjectById(testRun.projectId)
                    Object testPlan = tms.getTestPlanById(testRun.testPlanId)
                    testRunUrl = "\n[TestRunUrl](${env.TMS_URL}/projects/${project.globalId}/test-plans/${testPlan.globalId}/test-runs/${testRun.id})\n"
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
                        testRun.testResults.findAll { test ->
                            test.outcome.equalsIgnoreCase('InProgress')
                        }.findResults { test ->
                            testResults += [
                                configurationId: test.configuration.id,
                                autoTestExternalId: test.autoTest.externalId,
                                outcome: 'Skipped',
                                message: 'Skipped because another TestRun is already running',
                            ]
                        }
                        // Set tests of the testrun status to Skipped
                        log.info 'Set tests of the testrun status to Skipped'
                        tms.setResultToTestRuns(TEST_RUN_ID, testResults)
                        log.fatal 'Parallel start prohibited'
                    }
                    else if (testRun.stateName.equalsIgnoreCase('NotStarted')) {
                        // Start the TestRun
                        log.info 'Start the TestRun'
                        tms.actionTestRunById(TEST_RUN_ID, 'start')
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
                    Object testRun = tms.getTestRunById(TEST_RUN_ID)
                    Map testNameConfigs = [:]
                    testRun.testResults*.configuration.name
                    .unique()
                    .each { name ->
                        testRun.testResults.findAll { test ->
                            test.configuration.name == name
                        }
                        .findResults { test ->
                            testNameConfigs[name] = test.configuration.parameters.Priority
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
                        // Sorting by priority
                        List testsSort = []
                        List prioritys = tests*.priority.sort().unique()
                        prioritys.each { priority ->
                            testsSort += tests.findAll { test ->
                                test.priority == priority
                            }
                        }

                        testProjects += [
                            name: nameConfig.key,
                            priority: nameConfig.value,
                            autotests: testsSort
                        ]
                    }

                    writeJSON(json: testProjects, file: "${env.PROJECT_DIR}/.order.json")
                    log.info '\n' + writeJSON(json: testProjects, returnText: true)
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

        stage('Run tests:') {
            when {
                expression {
                    currentBuild.currentResult == 'SUCCESS'
                }
            }
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    startStage()
                    testProjects.each { project ->
                        String group = project.name
                        project.autotests.each { test ->
                            String testName = test.name
                            String startTimeTest = sysUtils.shStdout('date +%s%3N')
                            String outcome = 'Blocked'
                            String message = "Test_${testName}_passed"
                            String testLog = "${WORKSPACE}/${testName}_${group}.log"
                            String cmdRunTest = "ansible-playbook ${env.ANSIBLE_DIR}/${testName}.yml ${env.PLAY_EXTRAS} -l ${group} -e autotest=${testName} -e test_log=${testLog} | tee ${testLog}"

                            if (DRY_RUN) {
                                log.warning "DRY RUN: ${cmdRunTest}"
                            }
                            else {
                                String stateName = tms.getTestRunById(TEST_RUN_ID).stateName
                                stage(testName) {
                                    startStage()
                                    if (stateName.equalsIgnoreCase('Stopped')) {
                                        aborted('Aborted by Test IT')
                                    }
                                    else {
                                        try {
                                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                                                ansibleExecute(
                                                    command: cmdRunTest
                                                )
                                            }
                                        }
                                        catch (err) {
                                            outcome = 'Skipped'
                                            message = 'Skipped_by_test_fail'
                                            sleep 300
                                        }

                                        log.info 'Sending results to Test IT'
                                        withCredentials([string(credentialsId: env.TMS_AUTH_TOKEN, variable: 'VAULT_TMS_TOKEN')]) {
                                            String paramsSendResult = \
                                                "${env.PLAY_EXTRAS}" +
                                                " -e configuration=${group} " +
                                                " -e test_log=${testLog} " +
                                                " -e autotest=${testName} " +
                                                " -e outcome=${outcome} " +
                                                " -e message=${message} " +
                                                " -e BUILD_URL=${env.BUILD_URL} " +
                                                " -e test_start_time=${startTimeTest}" +
                                                " -e tms_api_url=${env.TMS_API_URL}" +
                                                " -e tms_token=${env.VAULT_TMS_TOKEN}"
                                            ansibleExecute(
                                                command: "ansible-playbook ${env.ANSIBLE_DIR}/tms_send_result.yml ${paramsSendResult}"
                                            )
                                        }
                                    }
                                    finishStage()
                                }
                            }
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
                String testRunStatus = tms.getTestRunById(TEST_RUN_ID).stateName
                if (testRunStatus in ['InProgress', 'NotStarted']) {
                    tms.actionTestRunById(TEST_RUN_ID, 'stop')
                    log.info 'Test run is Stoped'
                }

                startStage('test cleanup')
                String extras = env.PLAY_EXTRAS
                if (fileExists("${env.PROJECT_DIR}/.order.json")) {
                    Object order = readJSON(
                        file: "${env.PROJECT_DIR}/.order.json"
                    )
                    String groups = order.name.join(',')
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
