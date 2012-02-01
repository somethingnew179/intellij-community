package org.jetbrains.jet.j2k.visitors;

import com.google.common.collect.Sets;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * @author ignatov
 */
public class ThisVisitor extends JavaRecursiveElementVisitor {
    @NotNull
    private final Set<PsiMethod> myResolvedConstructors = Sets.newLinkedHashSet();

    @Override
    public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
        for (PsiReference r : expression.getReferences())
            if (r.getCanonicalText().equals("this")) {
                final PsiElement res = r.resolve();
                if (res != null && res instanceof PsiMethod && ((PsiMethod) res).isConstructor()) {
                    myResolvedConstructors.add((PsiMethod) res);
                }
            }
    }

    @Nullable
    public PsiMethod getPrimaryConstructor() {
        if (myResolvedConstructors.size() > 0) {
            PsiMethod first = myResolvedConstructors.toArray(new PsiMethod[myResolvedConstructors.size()])[0];
            for (PsiMethod m : myResolvedConstructors)
                if (m.hashCode() != first.hashCode()) {
                    return null;
                }
            return first;
        }
        return null;
    }
}
