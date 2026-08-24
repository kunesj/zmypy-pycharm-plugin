package works.kunesj.plugins.zmypy.services.parser

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Created when a line of mypy output cannot be parsed for some reason.
 * The goal is to handle cases when mypy fails to distinguish between throwing an exception and reporting a hit.
 *  - mypy exceptions _sometimes_ printed to stdout, mixing them into normal output, in which case even `-O json` is ignored
 *  - and sometimes after such and exception comes valuable json output
 */
class MypyParseException(val sourceJson: String, override val cause: SerializationException) :
    SerializationException(cause.message, cause)

object MypyOutputParser {

    private val withUnknownKeys = Json { ignoreUnknownKeys = true }

    /**
     * @throws SerializationException mypy _sometimes_ prints its own errors to stdout, mixing them into normal output,
     * in which case even `-O json` is ignored.
     */
    @Throws(MypyParseException::class)
    fun parse(json: String): Result<MypyMessage> {
        val result = try {
            withUnknownKeys.decodeFromString(MypyMessage.serializer(), json)
        } catch (e: SerializationException) {
            return Result.failure(MypyParseException(json, e))
        }
        return Result.success(adjustForPlatform(result))
    }
}

/**
 * Adjust line numbers
 *   from mypy/zuban: 1-based
 *   to intellij: 0-based
 *
 * Callers must pass messages with 1-based line and 0-based column, lowercase severity
 * (mypy's JSON and zuban's text output are both normalized to that shape before calling this).
 */
internal fun adjustForPlatform(message: MypyMessage): MypyMessage = message.copy(
    line = message.line - 1,
    severity = message.severity.uppercase()
)
