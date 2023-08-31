#!groovy

String bbCreds = env.JENKINS_URL =~ /(?i)qa-jenkins.ru/ ? '0fd7f3e0-957e-4e3a-8e3b-b383d7af9d8a' : 'git_creds'
String projectIdGlobal = ''
String testPlanIdGlobal = ''

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
            name: 'TEST_PLAN_URL',
            defaultValue: '',
            trim: true,
            description: 'example: http://testit.ru/projects/9395/test-plans/9452/results'
        ),
        choice(
            name: 'TEST_NESTING_LEVEL',
            choices: [
                '1',
                '2',
                '3',
            ],
            description: 'Уровень вложенности результатов тестов'
        ),
        booleanParam(
            name: 'AUTOTEST_SOURCE',
            defaultValue: false,
        ),
    ])
])

pipeline {
    agent {
        label 'masterLin'
    }

    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '50', artifactNumToKeepStr: '50'))
        ansiColor('xterm')
        timestamps()
    }

    stages {
        stage('Env') {
            steps {
                script {
                    startStage()
                    if (!params.TEST_PLAN_URL) {
                        log.fatal 'Не задан TEST_PLAN_URL'
                    }
                    projectIdGlobal = (params.TEST_PLAN_URL =~ /projects\/[0-9]+/)[0].replaceAll('projects/', '')
                    testPlanIdGlobal = (params.TEST_PLAN_URL =~ /test-plans\/[0-9]+/)[0].replaceAll('test-plans/', '')
                    finishStage()
                }
            }
        }

        stage('Copy Artifacts') {
            steps {
                script {
                    startStage()
                    Object testPlan = tms.getTestPlanById(testPlanIdGlobal)
                    String testPlanId = testPlan.id
                    Object lastResults = tms.getLastResultsByTestPlanId(testPlanIdGlobal)

                    List validatedTests = []
                    lastResults.each { lastResult ->
                        if (lastResult.status in ['Failed', 'Passed']) {
                            validatedTests += [[
                                'workItemId': lastResult.workItemId,
                                'targetId': lastResult.lastTestResult.id,
                                'configurationId': lastResult.configurationId,
                                'attachments': lastResult.lastTestResult.attachments,
                                'links': lastResult.lastTestResult.links,
                                'comment': lastResult.lastTestResult.comment
                            ]]
                        }
                    }

                    validatedTests.each { validatedTest ->
                        String query = \
                            "testPlanIds=${testPlanId}&" +
                            "configurationIds=${validatedTest.configurationId}&" +
                            "${params.AUTOTEST_SOURCE ? 'isAutomated=True&' : ''}" +
                            'outcomes=Blocked&' +
                            "Take=${params.TEST_NESTING_LEVEL}&" +
                            'orderBy=completedOn'
                        Object testResultsHistory = tms.getTestResultsHistoryByWorkItem(validatedTest.workItemId, query)

                        // Add last comment
                        String validatedTestComment = validatedTest.comment.toString() == 'null' ? '' : validatedTest.comment + '\n'
                        String testResultsHistoryComment = ''

                        // Add last links
                        List targetLinks = []
                        validatedTest.links.each { link ->
                            if (link.title != 'Artifacts_source') {
                                targetLinks += [[
                                    'title': link.title,
                                    'type': link.type,
                                    'url': link.url
                                ]]
                            }
                        }

                        // Add last attachments
                        List targetAttachments = []
                        validatedTest.attachments.each { attachment ->
                            targetAttachments += [[
                                'id': attachment.id
                            ]]
                        }

                        Integer i = 0
                        testResultsHistory.each { testResultHistory ->
                            i++
                            // Add history comments
                            testResultsHistoryComment += testResultHistory.comment.toString() == 'null' ? '' : testResultHistory.comment + '\n'
                            // Add history links
                            testResultHistory.links.each { link ->
                                if (link.title != 'Artifacts_source') {
                                    targetLinks += [[
                                        'title': link.title,
                                        'type': link.type,
                                        'url': link.url
                                    ]]
                                }
                            }
                            String linkUrl = env.TMS_URL +
                                "/projects/${projectIdGlobal}/test-plans/${testPlanIdGlobal}/results?testResultId=${testResultHistory.id}"
                            targetLinks += [[
                                'title': 'Artifacts_source_' + i,
                                'type': 'Related',
                                'url': linkUrl
                            ]]
                            // Add history attachments
                            testResultHistory.attachments.each { attachment ->
                                targetAttachments += [[
                                    'id': attachment.id
                                ]]
                            }
                        }

                        String targetComment = validatedTestComment + testResultsHistoryComment
                        if (targetComment.length() > 255) {
                            targetComment = validatedTestComment
                        }

                        targetLinks = targetLinks.unique()
                        targetAttachments = targetAttachments.unique()

                        Map bodyLastResult = [
                            'comment': targetComment,
                            'links': targetLinks,
                            'attachments': targetAttachments
                        ]
                        echo 'Update result: ' +
                            env.TMS_URL +
                            "/projects/${projectIdGlobal}/test-plans/${testPlanIdGlobal}/results?testResultId=${validatedTest.targetId}"
                        tms.editTestResultById(validatedTest.targetId, bodyLastResult)
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

void startStage(String msg = env.STAGE_NAME) {
    log.info 'STARTED STAGE: "' + msg + '"'
}

void finishStage(String msg = env.STAGE_NAME) {
    log.info 'FINISH STAGE: "' + msg + '"'
}
