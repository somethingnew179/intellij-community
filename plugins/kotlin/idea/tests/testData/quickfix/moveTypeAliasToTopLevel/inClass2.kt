// "Move typealias to top level" "true"
class C {
    class CC {
        <caret>typealias Foo = String

        fun bar(foo: Foo) {
        }
    }
}

fun baz() {}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.MoveTypeAliasToTopLevelFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.MoveTypeAliasToTopLevelFix