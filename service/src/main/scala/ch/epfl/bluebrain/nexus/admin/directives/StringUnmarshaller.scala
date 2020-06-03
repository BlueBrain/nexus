package ch.epfl.bluebrain.nexus.admin.directives

import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import ch.epfl.bluebrain.nexus.admin.exceptions.AdminError.InvalidFormat
import com.typesafe.scalalogging.Logger
import io.circe.parser._
import io.circe.{Decoder, Json}

object StringUnmarshaller {

  private val logger = Logger[this.type]

  /**
    * String => Json array => `A`
    *
    * @return unmarshaller for `A`
    */
  def unmarshallJsonArr[A: Decoder]: FromStringUnmarshaller[A] = unmarshaller { value =>
    Right(Json.arr(value.split(",").foldLeft(Vector.empty[Json])((acc, c) => acc :+ Json.fromString(c)): _*))
  }

  /**
    * String => Json string => `A`
    *
    * @return unmarshaller for `A`
    */
  def unmarshallJsonString[A: Decoder]: FromStringUnmarshaller[A] = unmarshaller { value =>
    Right(Json.fromString(value))
  }

  /**
    * String => Json => `A`
    *
    * @return unmarshaller for `A`
    */
  def unmarshallJson[A: Decoder]: FromStringUnmarshaller[A] = unmarshaller { value =>
    parse(value).left.map { err =>
      logger.warn(s"Failed to convert string '$value' to Json", err)
      InvalidFormat
    }
  }

  private def unmarshaller[A](
      f: String => Either[Throwable, Json]
  )(implicit dec: Decoder[A]): FromStringUnmarshaller[A] =
    Unmarshaller.strict[String, A] {
      case "" => throw Unmarshaller.NoContentException
      case string =>
        f(string).flatMap(_.as[A]) match {
          case Right(value) => value
          case Left(err)    => throw new IllegalArgumentException(err)
        }
    }

}
