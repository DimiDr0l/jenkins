#!groovy
/**
 * Печатает info-сообщение зеленым цветоа
 */
@SuppressWarnings('unused')
void info(String msg) {
    ansiColor('xterm') {
        print "\033[32m[INFO] ${msg}\033[0m"
    }
}

/**
 * Печатает warning-сообщение жёлтым
 */
@SuppressWarnings('unused')
void warning(String msg) {
    ansiColor('xterm') {
        print "\033[33m[WARNING] ${msg}\033[0m"
    }
}

/**
 * Печатает error-сообщение красным цветом
 */
@SuppressWarnings('unused')
void error(String msg) {
    ansiColor('xterm') {
        print "\033[91m[ERROR] ${msg}\033[0m"
    }
}

/**
 * Печатает fatal-сообщение красным цветом и завершает работу
 */
@SuppressWarnings('unused')
void fatal(String msg) {
    ansiColor('xterm') {
        print "\033[31m[FATAL] ${msg}\033[0m"
    }
    throw new Exception(msg)
}

/**
 * Печатает debug-сообщение синим цветом
 */
@SuppressWarnings('unused')
void debug(String msg) {
    ansiColor('xterm') {
        print "\033[34m[DEBUG] ${msg}\033[0m"
    }
}
