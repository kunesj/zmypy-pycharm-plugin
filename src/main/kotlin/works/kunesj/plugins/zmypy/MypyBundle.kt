package works.kunesj.plugins.zmypy

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.ZMypyBundle"

object ZMypyBundle {

    private val bundle = DynamicBundle(ZMypyBundle::class.java, BUNDLE)

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        bundle.getMessage(key, *params)

}
