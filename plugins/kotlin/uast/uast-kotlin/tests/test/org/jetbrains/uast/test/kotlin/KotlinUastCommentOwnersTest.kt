// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.uast.test.kotlin

import com.intellij.platform.uast.testFramework.common.CommentsTestBase
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.jetbrains.uast.UFile
import org.junit.Test


class KotlinUastCommentOwnersTest : AbstractKotlinCommentsTest(), CommentsTestBase {

    override val pluginMode: KotlinPluginMode
        get() = KotlinPluginMode.K1

    override fun check(testName: String, file: UFile) {
        super<CommentsTestBase>.check(testName, file)
    }

    @Test
    fun testCommentOwners() = doTest("CommentOwners")

    @Test
    fun testComments() = doTest("Comments")
}