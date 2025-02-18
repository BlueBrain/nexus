package io.circe

import io.circe.syntax.EncoderOps
import jakarta.json.JsonValue.{ValueType => JakartaValueType}
import jakarta.json.{Json => JakartaJson, JsonNumber => JakartaJsonNumber, JsonObject => JakartaJsonObject, JsonString, JsonValue => JakartaJsonValue}

import scala.jdk.CollectionConverters._
import java.math.{BigDecimal => JBigDecimal}

/**
  * Support for converting between Jakarta Json and Circe Based on
  * https://github.com/circe/circe-jackson/blob/master/shared/src/main/scala/io/circe/jackson/package.scala
  */
package object jakartajson {

  private val negativeZeroJson: Json = Json.fromDoubleOrNull(-0.0)

  /**
    * Converts given circe's Json instance to Jakarta's JsonValue Numbers with exponents exceeding Integer.MAX_VALUE are
    * converted to strings '''Warning: This implementation is not stack safe and will fail on very deep structures'''
    * @param json
    *   instance of circe's Json
    * @return
    *   converted JsonValue
    */
  def circeToJakarta(json: Json): JakartaJsonValue = json.fold(
    JakartaJsonValue.NULL,
    {
      case true  => JakartaJsonValue.TRUE
      case false => JakartaJsonValue.FALSE
    },
    number =>
      if (json == negativeZeroJson) {
        JakartaJson.createValue(number.toDouble)
      } else
        number match {
          case _: JsonBiggerDecimal | _: JsonBigDecimal =>
            number.toBigDecimal
              .map(bigDecimal => JakartaJson.createValue(bigDecimal.underlying))
              .getOrElse(JakartaJson.createValue(number.toString))
          case JsonLong(x)                              => JakartaJson.createValue(x)
          case JsonDouble(x)                            => JakartaJson.createValue(x)
          case JsonFloat(x)                             => JakartaJson.createValue(x.toDouble)
          case JsonDecimal(x)                           =>
            try {
              JakartaJson.createValue(new JBigDecimal(x))
            } catch {
              case _: NumberFormatException => JakartaJson.createValue(x)
            }
        },
    JakartaJson.createValue,
    array =>
      array
        .foldLeft(JakartaJson.createArrayBuilder) { case (builder, json) =>
          builder.add(circeToJakarta(json))
        }
        .build(),
    obj => {
      obj.toMap
        .foldLeft(JakartaJson.createObjectBuilder()) { case (builder, (key, value)) =>
          builder.add(key, circeToJakarta(value))
        }
        .build()
    }
  )

  def circeToJakarta(obj: JsonObject): JakartaJsonObject = obj.toMap
    .foldLeft(JakartaJson.createObjectBuilder()) { case (builder, (key, value)) =>
      builder.add(key, circeToJakarta(value))
    }
    .build()

  def jakartaJsonToCirce(value: JakartaJsonValue): Json =
    value.getValueType match {
      case JakartaValueType.NULL   => Json.Null
      case JakartaValueType.TRUE   => Json.True
      case JakartaValueType.FALSE  => Json.False
      case JakartaValueType.NUMBER =>
        val numberValue = value.asInstanceOf[JakartaJsonNumber]
        if (numberValue.isIntegral)
          Json.fromLong(numberValue.longValue())
        else
          Json.fromBigDecimal(numberValue.bigDecimalValue())
      case JakartaValueType.STRING => Json.fromString(value.asInstanceOf[JsonString].getString)
      case JakartaValueType.ARRAY  =>
        val values = value.asJsonArray().getValuesAs(classOf[JakartaJsonValue]).asScala.map(jakartaJsonToCirce)
        Json.fromValues(values)
      case JakartaValueType.OBJECT =>
        jakartaJsonToCirceObject(value.asJsonObject()).asJson
    }

  def jakartaJsonToCirceObject(value: JakartaJsonObject): JsonObject =
    JsonObject.fromIterable(value.asScala.view.mapValues(jakartaJsonToCirce))

}
