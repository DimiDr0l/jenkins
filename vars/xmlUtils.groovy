#!groovy
import groovy.xml.*

Object parseText(String html) {
    return (new XmlSlurper().parseText(html))
}

String serialize(Object xml) {
    return XmlUtil.serialize(xml)
}
