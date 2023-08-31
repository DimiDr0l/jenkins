#!groovy

// Projects
Object searchProjectByName(String projectName, Boolean isDeleted = false, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    Map mapBody = [
        'name': projectName,
        'isDeleted': isDeleted
    ]
    String body = writeJSON(json: mapBody, returnText: true)
    return sendRequest(
        url: tmsUrl + '/api/v2/projects/search',
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object getProjectById(String projectId, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    return sendRequest(
        url: tmsUrl + "/api/v2/projects/${projectId}",
        authPrivateToken: privateToken,
    )
}

Object getTestRunsByProjectId(String projectId, String testRunsStatus = 'inProgress', Boolean inProgress = true, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    String status = ''
    switch (testRunsStatus) {
        case 'inProgress':
            status = 'inProgress=true'
            break
        case 'notStarted':
            status = 'notStarted=true'
            break
        case 'stopped':
            status = 'stopped=true'
            break
        case 'completed':
            status = 'completed=true'
            break
    }
    return sendRequest(
        url: tmsUrl + "/api/v2/projects/${projectId}/testRuns?${status}",
        authPrivateToken: privateToken,
    )
}

Object getTestPlansByProjectId(Map aMap) {
    String projectId = aMap.projectId
    String filterAttributId = aMap.filterAttributId ?: env.TMS_ATTRIBUTE_CONFLUENCE_REPORT
    String filterAttributVal = aMap.filterAttributVal ?: 'true' // string!
    Boolean isDeleted = aMap.isDeleted ?: false
    String privateToken = aMap.privateToken ?: env.TMS_AUTH_TOKEN
    String tmsUrl = aMap.tmsUrl ?: env.TMS_URL

    Object testPlans = sendRequest(
        url: tmsUrl + "/api/v2/projects/${projectId}/testPlans?isDeleted=${isDeleted}",
        authPrivateToken: privateToken,
    )
    if (filterAttributId) {
        testPlans = testPlans.findAll { testPlan ->
            testPlan?.attributes[filterAttributId] && testPlan?.attributes[filterAttributId].equalsIgnoreCase(filterAttributVal)
        }
    }
    return testPlans
}

Object addTestPlansAttributesByProjectId(String projectId, List attributes, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    String body = writeJSON(json: attributes, returnText: true)
    return sendRequest(
        url: tmsUrl + "/api/v2/projects/${projectId}/testPlans/attributes",
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

// Test Plans
Object getTestPlanById(String testPlanId, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    return sendRequest(
        url: tmsUrl + '/api/v2/testPlans/' + testPlanId,
        authPrivateToken: privateToken,
    )
}

Object createTestPlan(Map testPlanConfig, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    String body = writeJSON(json: testPlanConfig, returnText: true)
    return sendRequest(
        url: tmsUrl + '/api/v2/testPlans',
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object updateTestPlan(Map testPlanConfig, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    String body = writeJSON(json: testPlanConfig, returnText: true)
    return sendRequest(
        url: tmsUrl + '/api/v2/testPlans',
        method: 'PUT',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object resetTestPointsStatusOfTestPlanId(String testPlanId, List testPoints, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    String body = writeJSON(json: testPoints, returnText: true)
    return sendRequest(
        url: tmsUrl + '/api/v2/testPlans/' + testPlanId + '/testPoints/reset',
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object getLastResultsByTestPlanId(String testPlanId, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    return sendRequest(
        url: tmsUrl + "/api/v2/testPlans/${testPlanId}/testPoints/lastResults",
        method: 'GET',
        authPrivateToken: privateToken,
    )
}

Object getAnalyticsByTestPlanId(String testPlanId, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    return sendRequest(
        url: tmsUrl + "/api/v2/testPlans/${testPlanId}/analytics",
        authPrivateToken: privateToken,
    )
}

Object getTestSuitesByTestPlanId(String testPlanId, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    return sendRequest(
        url: tmsUrl + "/api/v2/testPlans/${testPlanId}/testSuites",
        authPrivateToken: privateToken,
    )
}

Object getTestPlanConfiguration(String testPlanId, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    return sendRequest(
        url: tmsUrl + "/api/v2/testPlans/${testPlanId}/configurations",
        authPrivateToken: privateToken,
    )
}

// Test Runs
Object createTestRun(Map testRunConfig, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    String body = writeJSON(json: testRunConfig, returnText: true)
    return sendRequest(
        url: tmsUrl + '/api/v2/testRuns/byWorkItems',
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object setResultToTestRuns(String testRunId, List<Map<String, Object>> testResults, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    String body = writeJSON(json: testResults, returnText: true)
    return sendRequest(
        url: tmsUrl + "/api/v2/testRuns/${testRunId}/testResults",
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object getTestRunById(String testRunId, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    return sendRequest(
        url: tmsUrl + "/api/v2/testRuns/${testRunId}",
        authPrivateToken: privateToken,
    )
}

Object actionTestRunById(String testRunId, String action, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    // action == start, stop, complete
    return sendRequest(
        url: tmsUrl + "/api/v2/testRuns/${testRunId}/${action}",
        method: 'POST',
        authPrivateToken: privateToken,
    )
}

// Test results
Object editTestResultById(String testResultId, Map testResultMap, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    String body = writeJSON(json: testResultMap, returnText: true)
    return sendRequest(
        url: tmsUrl + "/api/v2/testResults/${testResultId}",
        method: 'PUT',
        body: body,
        authPrivateToken: privateToken,
    )
}

// Work Items
Object getWorkItemsByProjectId(String projectId, Boolean isDeleted = false, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    Map mapBody = [
        'filter': [
            'isDeleted': isDeleted,
            'projectIds': [
                projectId
            ]
        ]
    ]
    String body = writeJSON(json: mapBody, returnText: true)
    return sendRequest(
        url: tmsUrl + '/api/v2/workItems/search',
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object getWorkItemById(String workItemId, String versionId, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    return sendRequest(
        url: tmsUrl + "/api/v2/workItems/${workItemId}" + "${versionId ? '?versionId=' + versionId : ''}",
        authPrivateToken: privateToken,
    )
}

Object getTestResultsHistoryByWorkItem(String workItemId, String query = '', String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    return sendRequest(
        url: tmsUrl + "/api/v2/workItems/${workItemId}/testResults/history?${query}",
        method: 'GET',
        authPrivateToken: privateToken,
    )
}

// Test Points
Object getTestPointsByTestSuitesId(List testSuites, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    String body = writeJSON(json: ['testSuiteIds': testSuites], returnText: true)
    return sendRequest(
        url: tmsUrl + '/api/v2/testPoints/search',
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

// Webhooks
Object createWebhook(Map webhookStruct, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    String body = writeJSON(json: webhookStruct, returnText: true)
    return sendRequest(
        url: tmsUrl + '/api/v2/webhooks',
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object deleteWebhookById(String webhookId, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    return sendRequest(
        url: tmsUrl + '/api/v2/webhooks/' + webhookId,
        method: 'DELETE',
        authPrivateToken: privateToken,
    )
}

Object getWebhooksByProjectId(String projectId, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    return sendRequest(
        url: tmsUrl + '/api/v2/webhooks?projectId=' + projectId,
        authPrivateToken: privateToken,
    )
}

// Attachments
Object downloadAttachmentById(String attachmentId, String fileName, String privateToken = env.TMS_AUTH_TOKEN, String tmsUrl = env.TMS_URL) {
    return sendRequest(
        url: tmsUrl + '/api/v2/attachments/' + attachmentId,
        outputFile: fileName,
        authPrivateToken: privateToken,
    )
}
