package dev.ktrics.frontend.java

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.util.PsiTreeUtil
import dev.ktrics.ir.Resolution
import dev.ktrics.ir.SymbolRef
import dev.ktrics.ir.TypeRef

/**
 * Java classifier with resolution turned ON. Overrides the coupling/inheritance edges to
 * resolve via the shared symbol space (Java PSI `.resolve()` / `.resolveMethod()`), returning
 * qualified names and marking edges into compiled dependencies as external. Calculators are unchanged
 * — they just receive [Resolution.RESOLVED] refs now. An edge that fails to resolve (a
 * dependency missing from the classpath) degrades to name-based, exactly as the design promises.
 */
class ResolvedJavaClassifier : JavaClassifier() {
    override fun referencedTypes(scope: PsiElement): List<TypeRef> =
        PsiTreeUtil.collectElementsOfType(scope, PsiTypeElement::class.java)
            // A primitive (int/boolean/void) is fully known — NOT an unresolved class reference, and not a
            // coupling to another class. Skipping it keeps CBO to real class couplings and stops a primitive
            // from wrongly degrading the metric's RESOLVED stamp to name-based.
            .filterNot { it.type is PsiPrimitiveType }
            .mapNotNull { te ->
                // Unwrap array types to their component (`String[]` → `String`, `int[][]` → `int`) so the
                // element type resolves instead of degrading to a name-based `String[]` key. A primitive
                // component (`int[]`) is skipped, mirroring the bare-primitive skip above.
                var elementType = te.type
                while (elementType is PsiArrayType) elementType = elementType.componentType
                if (elementType is PsiPrimitiveType) return@mapNotNull null
                val resolved = (elementType as? PsiClassType)?.resolve()
                // A type variable (`T`, or the component of `T[]`) is not a class coupling — skip it, as the
                // Kotlin classifier does for KaTypeParameterType. Otherwise it resolves to a PsiTypeParameter
                // (which extends PsiClass) and emits a phantom RESOLVED coupling keyed by the bare `T`.
                if (resolved is PsiTypeParameter) return@mapNotNull null
                if (resolved != null) typeRef(resolved) else nameRef(elementType.presentableText)
            }.distinctBy { it.key }

    override fun calledSymbols(scope: PsiElement): List<SymbolRef> =
        PsiTreeUtil.collectElementsOfType(scope, PsiMethodCallExpression::class.java).map { call ->
            val method = call.resolveMethod()
            if (method != null) {
                SymbolRef(method.name, method.containingClass?.qualifiedName, Resolution.RESOLVED)
            } else {
                SymbolRef(call.methodExpression.referenceName ?: "?", null, Resolution.NAME_BASED)
            }
        }.distinctBy { it.key }

    /**
     * Resolution-aware call-graph edges: each call/type reference becomes its resolved fully-qualified
     * key (with multiplicity), so the call graph matches the exact target instead of every same-named
     * declaration. An edge that doesn't resolve degrades to its bare name.
     */
    override fun outgoingRefNames(scope: PsiElement): List<String> {
        val calls =
            PsiTreeUtil.collectElementsOfType(scope, PsiMethodCallExpression::class.java).map { call ->
                val method = call.resolveMethod()
                val owner = method?.containingClass?.qualifiedName
                when {
                    method == null -> call.methodExpression.referenceName
                    owner != null -> "$owner.${method.name}"
                    else -> method.name
                }
            }
        val types =
            PsiTreeUtil.collectElementsOfType(scope, PsiTypeElement::class.java)
                .filterNot { it.type is PsiPrimitiveType }
                .map { te ->
                    (te.type as? PsiClassType)?.resolve()?.qualifiedName ?: te.type.presentableText.takeIf { it.isNotBlank() }
                }
        return (calls + types).filterNotNull()
    }

    override fun supertypes(type: PsiElement): List<TypeRef> {
        val psiClass = type as? PsiClass ?: return super.supertypes(type)
        // Iterate the SYNTACTIC extends/implements references (like the base classifier) rather than
        // PsiClass.getSupers(), which silently drops any supertype that fails to resolve — a missing base
        // class would vanish from DIT/NOC. Each reference resolves to a RESOLVED ref when its target is on
        // the classpath, otherwise it degrades to a NAME_BASED ref, exactly as the design promises.
        val refs =
            listOfNotNull(psiClass.extendsList, psiClass.implementsList)
                .flatMap { it.referenceElements.toList() }
        return refs.mapNotNull { ref ->
            val resolved = ref.resolve() as? PsiClass
            if (resolved != null) {
                resolved.takeIf { it.qualifiedName != "java.lang.Object" }?.let { typeRef(it) }
            } else {
                ref.referenceName?.takeIf { ref.qualifiedName != "java.lang.Object" }?.let {
                    TypeRef(name = it, qualifiedName = ref.qualifiedName, packageName = null, resolution = Resolution.NAME_BASED)
                }
            }
        }
    }

    private fun typeRef(c: PsiClass): TypeRef =
        TypeRef(
            name = c.name ?: c.qualifiedName ?: "?",
            qualifiedName = c.qualifiedName,
            packageName = c.qualifiedName?.substringBeforeLast('.', ""),
            resolution = Resolution.RESOLVED,
            // came from a jar, not project source
            external = c is PsiCompiledElement,
        )

    private fun nameRef(name: String): TypeRef? = name.takeIf { it.isNotBlank() }?.let { TypeRef(it, null, null, Resolution.NAME_BASED) }
}
