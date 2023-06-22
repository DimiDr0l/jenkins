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
