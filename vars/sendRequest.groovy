#!groovy

Object call(Map aMap) {
    String url = aMap.url
    String auth = aMap.auth ?: ''
    String method = aMap.method ?: 'GET'
    String body = aMap.body ?: ''
    String outputFile = aMap.outputFile ?: ''
    String privateToken = aMap.authPrivateToken ?: ''
    Boolean quiet = aMap.quiet ?: false
    List cHeaders = aMap.headers ?: []
    String validResponseCodes = '100:499'

    Object respData = ''
    Map params = [
        url: url,
        contentType: 'APPLICATION_JSON_UTF8',
        acceptType: 'APPLICATION_JSON_UTF8',
        httpMode: method,
        validResponseCodes: validResponseCodes,
        quiet: quiet,
        customHeaders: cHeaders
    ]

    if (body) {
        params.requestBody = body
    }
    if (auth) { // Basic auth
        params.authentication = auth
    }
    if (privateToken) { // Token auth
        withCredentials([string(credentialsId: privateToken, variable: 'TOKEN')]) {
            params.customHeaders += [[
                name: 'Authorization',
                value: "PrivateToken ${TOKEN}",
                maskValue: true
            ]]
        }
    }
    if (outputFile) {
        params.outputFile = outputFile
        httpRequest(params)
    }
    else {
        Object response = httpRequest(params)

        if (response.status < 400) {
            try {
                respData = readJSON(text: response.content)
            } catch (Exception e) {
                respData = response.content
            }
        } else {
            log.fatal \
                "Response code: ${response.status}\n" +
                "Response content: ${response.content}\n"
        }
        return respData
    }
}
