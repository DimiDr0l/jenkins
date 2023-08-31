#!groovy
// https://docs.atlassian.com/ConfluenceServer/rest/7.4.18/
import groovy.json.JsonOutput

Object createContent(Map aMap) {
    String auth = aMap.auth ?: env.ATLASSIAN_CREDS
    String url = aMap.url ?: env.CONFLUENCE_URL
    String spaceKey = aMap.spaceKey ?: env.CONFLUENCE_SPACE_KEY
    String type = aMap.type ?: 'page'
    String title = aMap.title
    String body = aMap.body
    String ancestorsId = aMap.ancestorsId

    Map newContent = [
        'type': type,
        'title': title,
        'ancestors': [[
            'id': ancestorsId
        ]],
        'space': [
            'key': spaceKey
        ],
        'body': [
            'storage': [
                'value': body,
                'representation': 'storage'
            ]
        ]
    ]
    String request = JsonOutput.toJson(newContent) as String

    return sendRequest(
        url: url + '/rest/api/content',
        auth: auth,
        method: 'POST',
        body: request,
    )
}

Object updateContentById(Map aMap) {
    String auth = aMap.auth ?: env.ATLASSIAN_CREDS
    String url = aMap.url ?: env.CONFLUENCE_URL
    String type = aMap.type ?: 'page'
    String id = aMap.id
    String title = aMap.title
    String body = aMap.body
    Integer versionNumber = aMap.versionNumber

    Map newContent = [
        'version': [
            'number': versionNumber
        ],
        'title': title,
        'type': type,
        'body': [
            'storage': [
                'value': body,
                'representation': 'storage'
            ]
        ]
    ]
    String request = JsonOutput.toJson(newContent) as String

    return sendRequest(
        url: url + '/rest/api/content/' + id,
        auth: auth,
        method: 'PUT',
        body: request,
    )
}

Object getContentByTitle(Map aMap) {
    String auth = aMap.auth ?: env.ATLASSIAN_CREDS
    String url = aMap.url ?: env.CONFLUENCE_URL
    String spaceKey = aMap.spaceKey ?: env.CONFLUENCE_SPACE_KEY
    String type = aMap.type ?: 'page'
    String title = aMap.title
    String postingDay = aMap.postingDay ?: ''
    String expand = aMap.expand ?: 'space,body,version,container'
    Integer limit = aMap.limit ?: 10

    return sendRequest(
        url: url + '/rest/api/content?' +
            "type=${type}" +
            "&spaceKey=${spaceKey}" +
            "&title=${title}" +
            "${postingDay ? '&postingDay=' + postingDay : ''}" +
            "&expand=${expand}" +
            "&limit=${limit}",
        auth: auth,
    )
}

List<Map<String, Object>> getContentById(Map aMap) {
    String auth = aMap.auth ?: env.ATLASSIAN_CREDS
    String url = aMap.url ?: env.CONFLUENCE_URL
    String id = aMap.id
    String expand = aMap.expand ?: 'space,body.storage,version,container'

    return sendRequest(
        url: url + '/rest/api/content/' +
            id +
            "?expand=${expand}",
        auth: auth
    )
}

Object deleteContentById(Map aMap) {
    String auth = aMap.auth ?: env.ATLASSIAN_CREDS
    String url = aMap.url ?: env.CONFLUENCE_URL
    String id = aMap.id

    return sendRequest(
        url: url + '/rest/api/content/' +
            id +
            '?status=current',
        method: 'DELETE',
        auth: auth
    )
}

Object attachment(Map aMap) {
    String auth = aMap.auth ?: env.ATLASSIAN_CREDS
    String url = aMap.url ?: env.CONFLUENCE_URL
    String contentId = aMap.contentId
    String attachmentId = aMap.attachmentId ?: ''
    String file = aMap.file ?: ''
    String comment = aMap.comment ?: ''
    String action = aMap.action ?: '' // create, update, if blank then get list attachments
    Integer limit = aMap.limit ?: 100
    Integer start = aMap.start ?: 0

    String stdout = ''
    withCredentials([usernameColonPassword(credentialsId: auth, variable: 'auth')]) {
        String command = 'curl -sS -u $auth '
        if (action) {
            command += \
                ' -X POST -H "X-Atlassian-Token: nocheck" ' +
                " ${comment ? '-F "comment=' + comment + '"' : ''} " +
                " -F \"file=@${file}\" ${url}/rest/api/content/${contentId}/child/attachment" +
                "${action == 'update' ? '/' + attachmentId + '/data' : ''}"
        }
        else {
            command += " -X GET '${url}/rest/api/content/${contentId}/child/attachment?limit=${limit}&start=${start}'"
        }
        retry(3) {
            stdout = sh(script: command, returnStdout: true).trim()
        }
    }

    try {
        return readJSON(text: stdout)
    } catch (Exception e) {
        return stdout
    }
}

String templateTable(String action, List<Map<String, Object>> tables = []) {
    String content = ''
    switch (action) {
        case 'title':
            content = '<table class="confluenceTable"><tbody><tr>'
            tables.each { table ->
                content += '<th style="text-align: center;">' + table.value + '</th>'
            }
            content += '</tr>'
            break
        case 'table':
            content = '<tr>'
            tables.each { table ->
                String color = table?.color ?: ''
                Boolean statusLabel = table?.statusLabel ?: false
                if (statusLabel) {
                    color = color ?: 'Grey'
                    content += \
                        '<td style="text-align: center;">' +
                          '<div class="content-wrapper">' +
                            '<p>' +
                              '<ac:structured-macro ac:name="status">' +
                                '<ac:parameter ac:name="colour">' + color + '</ac:parameter>' +
                                '<ac:parameter ac:name="title">' + table.value + '</ac:parameter>' +
                              '</ac:structured-macro>' +
                            '</p>' +
                          '</div>' +
                        '</td>'
                }
                else if (color) {
                    content += '<td class="highlight-' + color + '" data-highlight-colour="' + color + '">' + table.value + '</td>'
                }
                else {
                    content += '<td>' + table.value + '</td>'
                }
            }
            content += '</tr>'
            break
        case 'end':
            content = '</tbody></table>'
            break
        default:
            log.fatal 'action error!'
            break
    }
    return content
}
