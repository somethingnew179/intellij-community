// "Rename to 'remAssign'" "true"
// DISABLE-ERRORS

object Rem {
    operator fun mod(x: Int) {}
    operator<caret> fun modAssign(x: Int) {}
}

fun test() {
    Rem % 1
    Rem.mod(1)
    Rem.modAssign(1)
    val c = Rem
    c %= 1
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.RenameModToRemFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.RenameModToRemFix