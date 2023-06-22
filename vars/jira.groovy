#!groovy
// https://developer.atlassian.com/cloud/jira/platform/rest/v2/api-group-issue-search/#api-rest-api-2-search-post
// https://developer.atlassian.com/server/jira/platform/jira-rest-api-examples/#searching-for-issues-examples

Object searchProjectByjql(Map aMap) {
    String auth = aMap.auth ?: env.ATLASSIAN_CREDS
    String url = aMap.url ?: env.JIRA_URL
    String project = aMap.project ?: 'SWNT'
    String text = aMap.text
    Map body = aMap.body ?: [
        jql: "project=${project} AND text~\"${text}\"",
        maxResults: 10,
        fields: [
            'id',
            'key'
        ]
    ]
    String request =  writeJSON(json: body, returnText: true)
    return sendRequest(
        url: url + '/rest/api/2/search',
        method: 'POST',
        body: request,
        auth: auth,
    )
}
