// WITH_RUNTIME
class `My$Exception` : Exception()
class `My$Exception2` : Exception()

@Throws(exceptionClasses = [`My$Exception`::class])
fun test() {
    <caret>throw `My$Exception2`()
}