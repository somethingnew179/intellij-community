// "Add non-null asserted (p!!) call" "true"

class SafeType {
    operator fun plus(arg: Int) {}
}

fun safeB(p: SafeType?) {
    val v = p <caret>+ 42
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.AddExclExclCallFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.AddExclExclCallFix