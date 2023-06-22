#!groovy

// Projects
Object searchProjectByName(String projectName, Boolean isDeleted = false, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    Map mapBody = [
        'name': projectName,
        'isDeleted': isDeleted
    ]
    String body = writeJSON(json: mapBody, returnText: true)
    return sendRequest(
        url: apiUrl + '/projects/search',
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object getProjectById(String projectId, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    return sendRequest(
        url: apiUrl + "/projects/${projectId}",
        authPrivateToken: privateToken,
    )
}

Object getTestRunsByProjectId(String projectId, Boolean inProgress = true, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    return sendRequest(
        url: apiUrl + "/projects/${projectId}/testRuns?InProgress=${inProgress}",
        authPrivateToken: privateToken,
    )
}

Object getTestPlansByProjectId(String projectId, Boolean isDeleted = false, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    return sendRequest(
        url: apiUrl + "/projects/${projectId}/testPlans?isDeleted=${isDeleted}",
        authPrivateToken: privateToken,
    )
}

Object addTestPlansAttributesByProjectId(String projectId, List attributes, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    String body = writeJSON(json: attributes, returnText: true)
    return sendRequest(
        url: apiUrl + "/projects/${projectId}/testPlans/attributes",
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

// Test Plans
Object createTestPlan(Map testPlanConfig, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    String body = writeJSON(json: testPlanConfig, returnText: true)
    return sendRequest(
        url: apiUrl + '/testPlans',
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object updateTestPlan(Map testPlanConfig, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    String body = writeJSON(json: testPlanConfig, returnText: true)
    return sendRequest(
        url: apiUrl + '/testPlans',
        method: 'PUT',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object resetTestPointsStatusOfTestPlanId(String testPlanId, List testPoints, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    String body = writeJSON(json: testPoints, returnText: true)
    return sendRequest(
        url: apiUrl + '/testPlans/' + testPlanId + '/testPoints/reset',
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object getLastResultsByTestPlanId(String testPlanId, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    return sendRequest(
        url: apiUrl + "/testPlans/${testPlanId}/testPoints/lastResults",
        method: 'GET',
        authPrivateToken: privateToken,
    )
}

Object getAnalyticsByTestPlanId(String testPlanId, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    return sendRequest(
        url: apiUrl + "/testPlans/${testPlanId}/analytics",
        authPrivateToken: privateToken,
    )
}

Object getTestSuitesByTestPlanId(String testPlanId, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    return sendRequest(
        url: apiUrl + "/testPlans/${testPlanId}/testSuites",
        authPrivateToken: privateToken,
    )
}

Object getTestPlanConfiguration(String testPlanId, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    return sendRequest(
        url: apiUrl + "/testPlans/${testPlanId}/configurations",
        authPrivateToken: privateToken,
    )
}

// Test Runs
Object createTestRun(Map testRunConfig, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    String body = writeJSON(json: testRunConfig, returnText: true)
    return sendRequest(
        url: apiUrl + '/testRuns/byWorkItems',
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object setResultToTestRuns(String testRunId, List<Map<String, Object>> testResults, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    String body = writeJSON(json: testResults, returnText: true)
    return sendRequest(
        url: apiUrl + "/testRuns/${testRunId}/testResults",
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object getTestRunById(String testRunId, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    return sendRequest(
        url: apiUrl + "/testRuns/${testRunId}",
        authPrivateToken: privateToken,
    )
}

Object actionTestRunById(String testRunId, String action, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    // action == start, stop, complete
    return sendRequest(
        url: apiUrl + "/testRuns/${testRunId}/${action}",
        method: 'POST',
        authPrivateToken: privateToken,
    )
}

// Work Items
Object getWorkItemsByProjectId(String projectId, Boolean isDeleted = false, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
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
        url: apiUrl + '/workItems/search',
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object getWorkItemById(String workItemId, String versionId, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    return sendRequest(
        url: apiUrl + "/workItems/${workItemId}" + "${versionId ? '?versionId=' + versionId : ''}",
        authPrivateToken: privateToken,
    )
}

// Test Points
Object getTestPointsByTestSuitesId(List testSuites, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    String body = writeJSON(json: ['testSuiteIds': testSuites], returnText: true)
    return sendRequest(
        url: apiUrl + '/testPoints/search',
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

// Webhooks
Object createWebhook(Map webhookStruct, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    String body = writeJSON(json: webhookStruct, returnText: true)
    return sendRequest(
        url: apiUrl + '/webhooks',
        method: 'POST',
        body: body,
        authPrivateToken: privateToken,
    )
}

Object deleteWebhookById(String webhookId, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    return sendRequest(
        url: apiUrl + '/webhooks/' + webhookId,
        method: 'DELETE',
        authPrivateToken: privateToken,
    )
}

Object getWebhooksByProjectId(String projectId, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    return sendRequest(
        url: apiUrl + '/webhooks?projectId=' + projectId,
        authPrivateToken: privateToken,
    )
}

// Attachments
Object downloadAttachmentById(String attachmentId, String fileName, String privateToken = env.TMS_AUTH_TOKEN, String apiUrl = env.TMS_API_URL) {
    return sendRequest(
        url: apiUrl + '/attachments/' + attachmentId,
        outputFile: fileName,
        authPrivateToken: privateToken,
    )
}
