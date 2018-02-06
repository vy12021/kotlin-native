# Current state of Kotlin language support in Kotlin/Native compared to Kotlin/JVM #

### `KClass` and reflection support ###
Reflection is not fully implemented and hence most of extension functions is not supported ([KClass doc reference](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html))
including annotations and properties: lateinit, delegate? or getting JVM specific `::class.java` 
See `KClass.kt` and `runtime/src/main/kotlin/kotlin/reflect` for implementation details

### Initialization ###
Class initialization differs from the JVM-based initialization.

### Cloneable ###
Some Kotlin builtin classes including `Array` or `Enum` are declared implicitly as `Cloneable` in Kotlin/JVM.
`Cloneable` and `clone()` isn't supported in Kotlin/Native

### Collections ###
Kotlin/Native collections are not open (final classes) and hence can not be extended, while most of Kotlin/JVM collections are open

### Constant folding and substitution ###
Two `const val` are not the same objects that will be `===` to each other.

### Type inference ###
Type inference may differ depending on what classes implement or extend.
```
inline fun <reified T> printCommonType(x: T, y: T) = println(T::class.simpleName)
```
This code may print different results with Kotlin/JVM and Kotlin/Native for `Int` and `String` due to different interfaces in implementation.