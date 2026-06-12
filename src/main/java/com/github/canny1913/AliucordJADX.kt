package com.github.canny1913

import jadx.api.metadata.ICodeNodeRef
import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.JadxPluginInfo
import jadx.api.plugins.JadxPluginInfoBuilder
import jadx.api.plugins.gui.JadxGuiContext
import jadx.core.dex.attributes.AType
import jadx.core.dex.info.ClassInfo
import jadx.core.dex.instructions.args.ArgType
import jadx.core.dex.instructions.args.PrimitiveType
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.FieldNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.utils.exceptions.JadxRuntimeException
import javax.swing.JOptionPane

class AliucordJADX : JadxPlugin {
	val options = AliucordJADXOptions()
	override fun getPluginInfo(): JadxPluginInfo? {
		return JadxPluginInfoBuilder.pluginId(PLUGIN_ID).name("Aliucord-JADX")
			.description("Aliucord helper plugin")
			.homepage("https://github.com/Canny1913/Aliucord-JADX")
			.requiredJadxVersion("1.5.1, r2333").build()
	}

	fun isHookActionEnabled(node: ICodeNodeRef) = node is MethodNode

	fun isFieldActionEnabled(node: ICodeNodeRef) =
		node is FieldNode && options.codegenLanguage.isKotlin()

	override fun init(context: JadxPluginContext?) {
		context?.registerOptions(options)
		val guiContext = context?.guiContext ?: return
		initGui(guiContext)
	}

	fun initGui(context: JadxGuiContext) {
		context.addPopupMenuAction(
			"Copy Aliucord hook snippet (before)", ::isHookActionEnabled, null
		) {
			snippetAction(context, it, HookType.BEFORE)
		}
		context.addPopupMenuAction(
			"Copy Aliucord hook snippet (after)", ::isHookActionEnabled, null
		) {
			snippetAction(context, it, HookType.AFTER)
		}
		context.addPopupMenuAction(
			"Copy Aliucord hook snippet (instead)", ::isHookActionEnabled, null
		) {
			snippetAction(context, it, HookType.INSTEAD)
		}
		context.addPopupMenuAction(
			"Copy Aliucord accessor snippet (field)", ::isFieldActionEnabled, null
		) {
			snippetAction(context, it)
		}
	}

	fun snippetAction(context: JadxGuiContext, node: ICodeNodeRef, hookType: HookType? = null) {
		try {
			val snippet = copySnippet(node, hookType)
			context.copyToClipboard(snippet)
		} catch (t: Throwable) {
			JOptionPane.showMessageDialog(
				context.mainFrame, t.localizedMessage, "Error", JOptionPane.ERROR_MESSAGE
			)

		}
	}

	fun copySnippet(node: ICodeNodeRef, hookType: HookType? = null): String {
		return when (node) {
			is MethodNode -> {
				copyMethodSnippet(node, hookType!!)
			}

			is FieldNode -> {
				copyFieldSnippet(node)
			}

			else -> {
				throw JadxRuntimeException("Unsupported node type.")
			}
		}
	}

	fun copyMethodSnippet(node: MethodNode, hookType: HookType): String {
		if (node.accessFlags.isAbstract) throw JadxRuntimeException("Cannot create a snippet of abstract method.")

		val language = options.codegenLanguage
		val method = node.methodInfo
		val reflectArgs = node.argTypes.map { mapArgType(node, it, true) }
		val methodArgs = node.argTypes.map { mapArgType(node, it, false) }
		val clazz = node.declaringClass
		var methodName = "\"${method.name}\""
		val hookTypeStr = hookType.asString(language)


		var className = getClassName(clazz, options.useFullName)
		if (language.isJava()) {
			className = "$className.class"
		}
		if (language.isKotlin()) {
			if (className.contains('$')) className = "`$className`"
			if (methodName.contains('$')) methodName = "$$\"$methodName\""
			if (node.accessFlags.isStatic) className = "$className?"
		}

		val formattedMethodArgs = methodArgs.mapIndexed { index, arg -> "p$index: $arg" }.joinToString(",\n", ",\n", "\n", DECONSTRUCTURED_ARG_LIMIT, "// too many args")
		val formatterPrefix = if (method.isConstructor) "\n" else ",\n"
		val formattedReflectArgs = reflectArgs.joinToString(",\n", formatterPrefix, "\n")
		return if (method.isConstructor) {
			if (options.codegenLanguage.isKotlin()) {
				"patcher.$hookTypeStr<$className>($formattedReflectArgs) { (param$formattedMethodArgs) -> \n // code \n }"
			} else {
				"patcher.patch($className.getDeclaredConstructor($formattedReflectArgs), new $hookTypeStr(param -> { \n // code \n }))"
			}
		} else {
			if (options.codegenLanguage.isKotlin()) {
				"patcher.$hookTypeStr<$className>($methodName$formattedReflectArgs) { (param$formattedMethodArgs) -> \n // code \n }"
			} else {
				"patcher.patch($className.getDeclaredMethod($methodName$formattedReflectArgs), new $hookTypeStr(param -> { \n // code \n }))"
			}
		}
	}

	fun copyFieldSnippet(node: FieldNode): String {
		if (node.accessFlags.isAbstract) throw JadxRuntimeException("Cannot create a getter snippet of abstract field.")

		val language = options.codegenLanguage
		val field = node.fieldInfo
		val fieldType = mapFieldType(node.type)
		val clazz = node.declaringClass
		var className = getClassName(clazz, options.useFullName)

		var fieldName = field.name
		if (language.isKotlin()) {
			if (className.contains('$')) className = "`$className`"
			if (fieldName.contains('$')) fieldName = "`$fieldName`"
		}
		return "var $className.$fieldName by accessField<$fieldType>()"
	}

	fun getClassName(clazz: ClassNode, short: Boolean): String {
		return if (clazz.isInner && clazz.contains(AType.RENAME_REASON)) { // bad workaround for supposedly "invalid" names
			if (short) clazz.classInfo.type.`object`.substringAfterLast('.') else clazz.classInfo.type.`object`
		} else {
			if (short) clazz.classInfo.shortName else clazz.classInfo.fullName
		}
	}

	fun mapFieldType(type: ArgType): String {
		val language = options.codegenLanguage
		if (language.isKotlin()) {
			if (type.canBeArray()) {
				val arrayElementType = type.arrayElement
				val processedType = processTypeName(arrayElementType.toString(), false)
				return "Array<$processedType>"
			}
			if (type.isGenericType && type.isObject && type.isTypeKnown) {
				return processTypeName("java.lang.Object", false)
			}
			if (type.isGeneric && type.isObject) {
				val generics = type.genericTypes.joinToString(", ") { "*" }
				val processedType = processTypeName(type.`object`, false)
				return "$processedType<$generics>"
			}
			if (type.isPrimitive) {
				val pt = type.primitiveType
				return PRIMITIVE_TYPE_MAPPING[pt.toString()]!!
			}
		}
		return processTypeName(type.toString(), false)
	}

	fun mapArgType(node: MethodNode, type: ArgType, asClass: Boolean): String {
		val language = options.codegenLanguage
		if (type.isGeneric && type.isObject) {
			val processedType = processTypeName(type.`object`, asClass)
			if (!asClass) {
				val generics = type.genericTypes.joinToString(", ") { "*" }
				return "$processedType<$generics>"
			} else {
				return processedType
			}
		}
		if (type.isGenericType && type.isObject && type.isTypeKnown) {
			return processTypeName("java.lang.Object", asClass)
		}
		if (type.isPrimitive) {
			if (language.isJava()) return "$type.class"
			val pt = type.primitiveType
			val ptType = when (pt) {
				PrimitiveType.BOOLEAN -> "Boolean"
				PrimitiveType.CHAR -> "Char"
				PrimitiveType.BYTE -> "Byte"
				PrimitiveType.SHORT -> "Short"
				PrimitiveType.INT -> "Int"
				PrimitiveType.FLOAT -> "Float"
				PrimitiveType.LONG -> "Long"
				PrimitiveType.DOUBLE -> "Double"
				PrimitiveType.OBJECT -> "Any"
				PrimitiveType.ARRAY -> "Array"
				PrimitiveType.VOID -> "Void"
				else -> throw JadxRuntimeException("Unknown or null primitive type: $type")
			}
			return processTypeName(ptType, asClass)
		}
		val objectType = if (type.`object`.contains("$")) {
			ClassInfo.fromType(node.root(), type).fullName
		} else {
			type.`object`
		}
		return processTypeName(objectType, asClass)
	}

	fun processTypeName(type: String, asClass: Boolean): String {
		val useFullName = options.useFullName
		val language = options.codegenLanguage
		var newType = type
		if (!useFullName) {
			newType = type.substringAfterLast('.') // hacky but idc
		}
		val isBoxed = type in BOXED_TYPE_MAPPING
		if (isBoxed) {
			newType = BOXED_TYPE_MAPPING[type]!!
		}
		if (asClass) {
			if (language.isJava()) return "$newType.class"
			return if (isBoxed) {
				"$newType::class.javaObjectType"
			} else {
				"$newType::class.java"
			}
		} else {
			return newType
		}
	}

	internal companion object {
		const val PLUGIN_ID = "jadx-aliucord"

		private val PRIMITIVE_TYPE_MAPPING: Map<String, String> = mapOf(
			"int" to "Int",
			"byte" to "Byte",
			"short" to "Short",
			"long" to "Long",
			"float" to "Float",
			"double" to "Double",
			"char" to "Char",
			"boolean" to "Boolean"
		)
		private val BOXED_TYPE_MAPPING: Map<String, String> = mapOf(
			"java.lang.Integer" to "Int",
			"java.lang.Byte" to "Byte",
			"java.lang.Short" to "Short",
			"java.lang.Long" to "Long",
			"java.lang.Float" to "Float",
			"java.lang.Double" to "Double",
			"java.lang.Character" to "Char",
			"java.lang.Boolean" to "Boolean",
			"java.lang.String" to "String",
			"java.lang.Object" to "Any"
		)
		// Aliucord core only allows 11 args to be destructured in hook lambda
		private const val DECONSTRUCTURED_ARG_LIMIT = 11
	}
}

