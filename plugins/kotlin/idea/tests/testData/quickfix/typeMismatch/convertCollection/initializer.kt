// "Convert expression to 'List' by inserting '.toList()'" "true"
// WITH_STDLIB

fun foo(a: Sequence<String>) {
    val strings: List<String> = a<caret>
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.ConvertCollectionFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.ConvertCollectionFix