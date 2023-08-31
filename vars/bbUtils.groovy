#!groovy
// https://developer.atlassian.com/server/bitbucket/rest/v811/api-group-repository/#api-group-repository

String getRawFile(Map aMap) {
    String path = aMap.path
    String project = aMap.project ?: env.BITBUCKET_PROJECT
    String repo = aMap.repo ?: env.BITBUCKET_REPO
    String branch = aMap.branch ?: 'master'
    String authCreds = aMap.authCreds ?: env.BITBUCKET_CREDS
    String bbUrl = aMap.bbUrl ?: env.BITBUCKET_URL
    return sendRequest(
        url: "${bbUrl}/rest/api/1.0/projects/${project}/repos/${repo}/raw/${path}?at=refs%2Fheads%2F${branch}",
        auth: authCreds,
    )
}

Object getCommits(Map aMap) {
    String project = aMap.project ?: env.BITBUCKET_PROJECT
    String repo = aMap.repo ?: env.BITBUCKET_REPO
    String branch = aMap.branch ?: 'master'
    String authCreds = aMap.authCreds ?: env.BITBUCKET_CREDS
    String bbUrl = aMap.bbUrl ?: env.BITBUCKET_URL
    Integer limit = aMap.limit ?: 10
    return sendRequest(
        url: "${bbUrl}/rest/api/1.0/projects/${project}/repos/${repo}/commits?limit=${limit}&until=refs%2Fheads%2F${branch}",
        auth: authCreds,
    )
}

Object getPrs(Map aMap) {
    String project = aMap.project ?: env.BITBUCKET_PROJECT
    String repo = aMap.repo ?: env.BITBUCKET_REPO
    String authCreds = aMap.authCreds ?: env.BITBUCKET_CREDS
    String bbUrl = aMap.bbUrl ?: env.BITBUCKET_URL
    Integer limit = aMap.limit ?: 10
    return sendRequest(
        url: "${bbUrl}/rest/api/1.0/projects/${project}/repos/${repo}/pull-requests?limit=${limit}",
        auth: authCreds,
    )
}

Object getCommitInfoByHash(Map aMap) {
    String hash = aMap.hash
    String project = aMap.project ?: env.BITBUCKET_PROJECT
    String repo = aMap.repo ?: env.BITBUCKET_REPO
    String authCreds = aMap.authCreds ?: env.BITBUCKET_CREDS
    String bbUrl = aMap.bbUrl ?: env.BITBUCKET_URL
    return sendRequest(
        url: "${bbUrl}/rest/api/1.0/projects/${project}/repos/${repo}/commits/${hash}",
        auth: authCreds,
    )
}

void notify(Map aMap) {
    String stashServerBaseUrl = aMap.stashServerBaseUrl ?: env.BITBUCKET_URL
    String credentialsId = aMap.credentialsId ?: env.BITBUCKET_CREDS
    String projectKey = aMap.projectKey ?: env.BITBUCKET_PROJECT
    String commitSha1 = aMap.commitSha1 ?: null
    String buildStatus = aMap.buildStatus ?: currentBuild.result

    notifyBitbucket(
        stashServerBaseUrl: stashServerBaseUrl,
        credentialsId: credentialsId,
        projectKey: projectKey,
        commitSha1: commitSha1,
        buildStatus: buildStatus
    )
}

Integer commentPr(Map aMap) {
    String bbUrl = aMap.bbUrl ?: env.BITBUCKET_URL
    String project = aMap.project ?: env.BITBUCKET_PROJECT
    String repo = aMap.repo ?: env.BITBUCKET_REPO
    String authCreds = aMap.authCreds ?: env.BITBUCKET_CREDS
    String action = aMap.action ?: 'add'
    Integer prId = aMap.prId
    Integer commentId = aMap.commentId
    String text = aMap.text ?: ''
    String status = aMap.status ?: currentBuild.result ?: 'DEFAULT'
    Map statusList = [
        'SUCCESS': '✅',
        'FAILURE': '⛔',
        'UNSTABLE': '🟠',
        'INPROGRESS': '🔄',
        'ABORTED': '⛔',
        'DEFAULT': '🤷️',
    ]

    String url = "${bbUrl}/rest/api/1.0/projects/${project}/repos/${repo}/pull-requests/${prId}/comments"
    switch (action) {
        case 'add':
            String requestBody = writeJSON(json: ['text': statusList[status] + " ${status}\n${text}"], returnText: true)
            Object res = sendRequest(
                url: url,
                auth: authCreds,
                method: 'POST',
                body: requestBody
            )
            return res.id
        case 'delete':
            sendRequest(
                url: "${url}/${commentId}?version=0",
                auth: authCreds,
                method: 'DELETE'
            )
            break
        default:
            log.fatal 'bbUtils.commentPr() Not valide parameters'
            break
    }
}

Boolean checkBranch(Map aMap) {
    String project = aMap.project ?: env.BITBUCKET_PROJECT
    String repo = aMap.repo ?: env.BITBUCKET_REPO
    String branch = aMap.branch
    String authCreds = aMap.authCreds ?: env.BITBUCKET_CREDS
    String bbUrl = aMap.bbUrl ?: env.BITBUCKET_URL
    Integer limit = aMap.limit ?: 100
    Object data = sendRequest(
        url: "${bbUrl}/rest/api/1.0/projects/${project}/repos/${repo}/branches?limit=${limit}",
        auth: authCreds,
    )
    Boolean flag = false
    for (br in data.values) {
        if (br.displayId == branch) {
            flag = true
            break
        }
    }

    return flag
}

Object createPr(Map aMap) {
    String project = aMap.project ?: env.BITBUCKET_PROJECT
    String repo = aMap.repo ?: env.BITBUCKET_REPO
    String authCreds = aMap.authCreds ?: env.BITBUCKET_CREDS
    String bbUrl = aMap.bbUrl ?: env.BITBUCKET_URL
    String fromRef = aMap.fromRef
    String toRef = aMap.toRef ?: 'master'
    String title = aMap.title
    String description = aMap.description ?: title

    Map aMapBody = [
        'title': title,
        'description': description,
        'fromRef': [
            'id': "refs/heads/${fromRef}",
            'repository': repo,
            'project': [
                'key': project
            ]
        ],
        'toRef': [
            'id': "refs/heads/${toRef}",
            'repository': repo,
            'project': [
                'key': project
            ]
        ]
    ]
    String requestBody = writeJSON(json: aMapBody, returnText: true)
    return sendRequest(
        url: "${bbUrl}/rest/api/1.0/projects/${project}/repos/${repo}/pull-requests",
        auth: authCreds,
        method: 'POST',
        body: requestBody
    )
}
