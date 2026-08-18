/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.applemusic.parser

/**
 * 尝试调用对象方法
 */
fun callMethod(any: Any, name: String, vararg args: Any?): Any? =
    runCatching {
        val method = any.javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterTypes.size == args.size &&
                    m.parameterTypes.zip(args.toList()).all { (param, arg) ->
                        arg == null || param.isAssignableFrom(arg.javaClass)
                    }
        } ?: any.javaClass.getDeclaredMethod(
            name,
            *args.filterNotNull().map { it.javaClass }.toTypedArray()
        )
        method.isAccessible = true
        method.invoke(any, *args)
    }.getOrNull()
