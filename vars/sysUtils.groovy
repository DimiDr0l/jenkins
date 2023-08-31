#!groovy
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat

String getDate(String dateFormat = 'yyyy-MM-dd_HH-mm') {
    return new SimpleDateFormat(dateFormat).format(new Date())
}

Boolean compareCurrentDate(String compareDate, Integer offset = 0, String format = 'yyyy-MM-dd') {
    DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern(format)
    String now = LocalDate.now().format(dateFormat)
    LocalDate creation = LocalDate.parse(compareDate, dateFormat)
    return LocalDate.parse(now, dateFormat).minusDays(offset) <= creation
}

long convertDateToTimestamp(String sDate, String format = "yyyy-MM-dd'T'HH:mm:ss") {
    SimpleDateFormat date = new SimpleDateFormat(format)
    long millis = date.parse(sDate).time
    return millis
}

String shStdout(String command) {
    return sh(
        script: command,
        returnStdout: true
    ).trim()
}

String getBuildCauses() {
    Object causeDescroption = currentBuild?.buildCauses[0].shortDescription
    String trigger = ''

    switch (causeDescroption) {
        case ~/(?i)Generic Cause.*/:
            trigger = 'generic'
            break
        case ~/(?i)Started by timer.*/:
            trigger = 'timer'
            break
        case ~/(?i)Started by user.*/:
            trigger = 'user'
            break
        default:
            trigger = 'undefine'
            println 'undefine trigger: ' + causeDescroption
            break
    }
    return trigger
}
