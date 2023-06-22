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

Object downstreamJob
Map jobInfo = [:]
REGEXP_PATTERN = /check_ci:.*/
TEST_RUN_ID = ''
KIBCHAOS_BRANCH = ''
GIT_HASH = ''
TMS_TEST_TAGS = ''
TMS_PROJECT_NAME = ''

properties([
    parameters([
        string(
            name: 'TMS_TEST_TAGS',
            defaultValue: 'blade_cpu',
            trim: true,
            description: 'List of tests separated by commas'
        ),
        string(
            name: 'PROJECT_SETTINGS_ID',
            defaultValue: 'chaos-team-dm-dev',
            trim: true,
            description: \
                'Name folder in project settings<br>'
        ),
        booleanParam(
            name: 'CRON_RUN',
            defaultValue: false,
        ),
    ])
])

pipeline {
    agent {
        label 'linux'
    }

    triggers {
        parameterizedCron('''
            00 01 * * * %CRON_RUN=true;TMS_TEST_TAGS=blade_cpu,blade_ram,tc_network_loss,tc_network_delay,tc_network_corrupt,tc_network_combo,stress_hdd_rw,stress_hdd_space,process_pause,reboot;
            00 02 * * * %CRON_RUN=true;TMS_TEST_TAGS=os_kill_pods,os_fail_pods,os_blade_cpu_containers,os_blade_ram_containers;
        ''')
        GenericTrigger(
            genericVariables: [
                [
                    key: 'PUSH_BRANCH',
                    value: '$.push.changes[0].new.name',
                    expressionType: 'JSONPath',
                    defaultValue: '-'
                ],
                [
                    key: 'PUSH_HASH',
                    value: '$.push.changes[0].new.target.hash',
                    expressionType: 'JSONPath',
                    defaultValue: '-'
                ],
                [
                    key: 'PR_FROM_HASH',
                    value: '$.pullrequest.fromRef.commit.hash',
                    expressionType: 'JSONPath',
                    defaultValue: '-'
                ],
                [
                    key: 'PR_ID',
                    value: '$.pullrequest.id',
                    expressionType: 'JSONPath',
                    defaultValue: '-'
                ],
                [
                    key: 'PR_LINK',
                    value: '$.pullrequest.link',
                    expressionType: 'JSONPath',
                    defaultValue: '-'
                ],
                [
                    key: 'PR_FROM',
                    value: '$.pullrequest.fromRef.branch.name',
                    expressionType: 'JSONPath',
                    defaultValue: '-'
                ],
                [
                    key: 'PR_TO',
                    value: '$.pullrequest.toRef.branch.name',
                    expressionType: 'JSONPath',
                    defaultValue: '-'
                ],
                [
                    key: 'PR_COMMENT',
                    value: '$.comment',
                    expressionType: 'JSONPath',
                    defaultValue: '-'
                ],
            ],
            causeString: 'Triggered on BB',
            tokenCredentialId: 'bb-trigger-ci-token',
            printContributedVariables: false,
            printPostContent: true,
            silentResponse: true,
        )
    }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '60', artifactNumToKeepStr: '60'))
        timestamps()
        timeout(time: 3, unit: 'HOURS')
        ansiColor('xterm')
    }

    stages {
        stage('Env') {
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    TMS_TEST_TAGS = params.TMS_TEST_TAGS.split(',')
                    wrap([$class: 'BuildUser']) {
                        if (env.BUILD_USER) {
                            currentBuild.result = 'ABORTED'
                            log.fatal 'Manual start disabled'
                        }
                    }
                    if (params.CRON_RUN) {
                        KIBCHAOS_BRANCH = 'master'
                        GIT_HASH = bbUtils.getCommits(limit: 1, branch: KIBCHAOS_BRANCH).values[0].id
                    } else {
                        KIBCHAOS_BRANCH = "${env.PUSH_BRANCH == '-' ? env.PR_FROM : env.PUSH_BRANCH ?: '-'}"
                        GIT_HASH = "${env.PUSH_HASH == '-' ? env.PR_FROM_HASH : env.PUSH_HASH ?: '-'}"
                    }
                    if (!KIBCHAOS_BRANCH || KIBCHAOS_BRANCH == '-' || !GIT_HASH || GIT_HASH == '-') {
                        log.fatal 'Webhook error'
                    }
                    // Get TMS_PROJECT_NAME from repo project-settings
                    String rawFile = bbUtils.getRawFile(
                        path: params.PROJECT_SETTINGS_ID + '/config.yml',
                        repo: 'project-settings'
                    )
                    TMS_PROJECT_NAME = readYaml(text: rawFile).tms_project_name

                    if (!params.CRON_RUN) {
                        // Get commit message and search REGEXP_PATTERN (example: "check_ci: blade_cpu,blade_ram")
                        String commitMessage = bbUtils.getCommitInfoByHash(hash: GIT_HASH).message
                        if (commitMessage =~ REGEXP_PATTERN) {
                            TMS_TEST_TAGS = (commitMessage =~ REGEXP_PATTERN)[0]
                                .replaceAll('check_ci:', '')
                                .trim()
                                .replaceAll(/\s.*/, '')
                                .trim()
                                .split(',')
                        }

                        // Get PRs and find by hash
                        if (!env.PR_ID || env.PR_ID == '-') {
                            Object prs = bbUtils.getPrs(limit: 10)
                            if (prs.size > 0) {
                                prs.values.findAll { pr ->
                                    pr.fromRef.latestCommit == GIT_HASH
                                }.
                                findResults { pr ->
                                    env.PR_ID = pr.id
                                }
                                log.info "PR_ID: ${env.PR_ID}"
                            }
                        }

                        // Comment PR if this PR
                        if (env.PR_ID && env.PR_ID != '-') {
                            jobInfo.commentId = bbUtils.commentPr(
                                prId: env.PR_ID.toInteger(),
                                text: 'build',
                                status: 'INPROGRESS'
                            )
                        }
                    }

                    log.info \
                        "KIBCHAOS_BRANCH: ${KIBCHAOS_BRANCH}\n" +
                        "GIT_HASH: ${GIT_HASH}\n" +
                        "PUSH_BRANCH: ${env.PUSH_BRANCH}\n" +
                        "PUSH_HASH: ${env.PUSH_HASH}\n" +
                        "PR_FROM_HASH: ${env.PR_FROM_HASH}\n" +
                        "PR_COMMENT: ${env.PR_COMMENT}\n" +
                        "PR_ID: ${env.PR_ID}\n" +
                        "PR_LINK: ${env.PR_LINK}\n" +
                        "PR_FROM: ${env.PR_FROM}\n" +
                        "PR_TO: ${env.PR_TO}\n" +
                        "TMS_TEST_TAGS: ${TMS_TEST_TAGS}"
                    // Notify BB in progress build
                    bbUtils.notify(commitSha1: GIT_HASH, buildStatus: 'INPROGRESS')
                }
            }
        }

        stage('Test IT ') {
            when {
                expression {
                    currentBuild.currentResult == 'SUCCESS'
                }
            }
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    String projectId = tms.searchProjectByName(TMS_PROJECT_NAME)[0].id
                    String testPlanId = tms.getTestPlansByProjectId(projectId)[0].id
                    List testSuites = tms.getTestSuitesByTestPlanId(testPlanId)
                        .collectMany { cID -> cID.id ?: [] }

                    Object testPoints = tms.getTestPointsByTestSuitesId(testSuites)
                    List testPointsNoStatus = testPoints.collectMany { point ->
                        point.status == 'NoResults' ? [] : point.id
                    }
                    // reset test plan status
                    if (testPointsNoStatus) {
                        tms.resetTestPointsStatusOfTestPlanId(testPlanId, testPointsNoStatus)
                    }
                    Object workItems = tms.getWorkItemsByProjectId(projectId)

                    Map testRunConfig = [
                        'configurationIds': [],
                        'workitemIds': [],
                        'projectId': projectId,
                        'testPlanId': testPlanId
                    ]
                    TMS_TEST_TAGS.each { tag ->
                        testPoints.findAll { point ->
                            tag in point.tags
                        }.
                        findResults { point ->
                            testRunConfig.configurationIds.add(point.configurationId)
                        }

                        workItems.findAll { workItem ->
                            tag in workItem.tagNames
                        }.
                        findResults { workItem ->
                            testRunConfig.workitemIds.add(workItem.id)
                        }
                    }
                    testRunConfig.configurationIds.unique()
                    testRunConfig.workitemIds.unique()
                    // log.info 'testRunConfig: ' + testRunConfig
                    Object createTestRun = tms.createTestRun(testRunConfig)
                    TEST_RUN_ID = createTestRun.id

                    log.info 'TEST_RUN_ID: ' + TEST_RUN_ID
                }
            }
        }

        stage('Run ansible job') {
            when {
                expression {
                    currentBuild.currentResult == 'SUCCESS'
                }
            }
            steps {
                script {
                    env.LAST_STAGE = env.STAGE_NAME
                    downstreamJob = build(
                        job: 'testit-auto-d',
                        wait: true, // Ожидать завершения downstream job
                        propagate: true, // Учитывать результат downstream job
                        parameters: [
                            string(name: 'TEST_RUN_ID', value: TEST_RUN_ID),
                            string(name: 'KIBCHAOS_BRANCH', value: KIBCHAOS_BRANCH),
                            string(name: 'PROJECT_SETTINGS_ID', value: params.PROJECT_SETTINGS_ID),
                            [$class: 'BooleanParameterValue', name: 'DEBUG', value: false],
                        ]
                    )
                }
            }
        }
    }

    post {
        always {
            script {
                if (env.LAST_STAGE != 'Env') {
                    bbUtils.notify(commitSha1: GIT_HASH)
                    if (env.PR_ID && env.PR_ID != '-') {
                        bbUtils.commentPr(
                            prId: env.PR_ID.toInteger(),
                            action: 'delete',
                            commentId: jobInfo.commentId
                        )
                        jobInfo.commentId = bbUtils.commentPr(
                            prId: env.PR_ID.toInteger(),
                            text: \
                                "TMS_TEST_TAGS: ${TMS_TEST_TAGS}\n" +
                                "**Jenkins: ${downstreamJob.getAbsoluteUrl()}console **",
                        )
                    }
                }
            }
        }

        cleanup {
            cleanWs()
        }
    }
}
