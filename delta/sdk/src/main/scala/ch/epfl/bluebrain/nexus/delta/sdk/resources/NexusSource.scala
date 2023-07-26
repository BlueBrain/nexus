package ch.epfl.bluebrain.nexus.delta.sdk.resources

import io.circe.Decoder.Result
import io.circe.{Decoder, DecodingFailure, HCursor, Json}
import pureconfig.error.{CannotConvert, ConfigReaderFailures, ConvertFailure}
import pureconfig.{ConfigCursor, ConfigReader}

final case class NexusSource(value: Json) extends AnyVal

object NexusSource {

  sealed trait DecodingOption

  object DecodingOption {
    final case object Strict extends DecodingOption

    final case object Lenient extends DecodingOption

    implicit val decodingOptionConfigReader: ConfigReader[DecodingOption] = {
      new ConfigReader[DecodingOption] {
        private val stringReader = implicitly[ConfigReader[String]]
        override def from(cur: ConfigCursor): ConfigReader.Result[DecodingOption] = {
          stringReader.from(cur).flatMap {
            case "strict"  => Right(Strict)
            case "lenient" => Right(Lenient)
            case other     =>
              Left(
                ConfigReaderFailures(
                  ConvertFailure(
                    CannotConvert(
                      other,
                      "DecodingOption",
                      s"values can only be 'strict' or 'lenient'"
                    ),
                    cur
                  )
                )
              )
          }
        }
      }
    }
  }

  private val strictDecoder = new Decoder[NexusSource] {
    private val decoder = implicitly[Decoder[Json]]

    override def apply(c: HCursor): Result[NexusSource] = {
      decoder(c).flatMap { json =>
        val underscoreFields = json.asObject.toList.flatMap(_.keys).filter(_.startsWith("_"))
        Either.cond(
          underscoreFields.isEmpty,
          NexusSource(json),
          DecodingFailure(
            s"Field(s) starting with _ found in payload: ${underscoreFields.mkString(", ")}",
            c.history
          )
        )
      }
    }
  }

  private val lenientDecoder = implicitly[Decoder[Json]].map(NexusSource(_))

  implicit def nexusSourceDecoder(implicit decodingOption: DecodingOption): Decoder[NexusSource] = {
    decodingOption match {
      case DecodingOption.Lenient => lenientDecoder
      case DecodingOption.Strict  => strictDecoder
    }
  }
}
