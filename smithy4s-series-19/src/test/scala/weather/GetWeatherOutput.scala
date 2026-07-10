package weather

import smithy4s.{Hints, Schema, ShapeId, ShapeTag}
import smithy4s.schema.Schema.{int, string, struct}

final case class GetWeatherOutput(
    weather: String,
    statusCode: Int = 200,
    degrees: Option[Int] = None
)

object GetWeatherOutput extends ShapeTag.Companion[GetWeatherOutput] {
  val id: ShapeId = ShapeId("weather", "GetWeatherOutput")

  val hints: Hints = Hints(
    smithy.api.Output()
  ).lazily

  // constructor using the original order from the spec
  private def make(weather: String, degrees: Option[Int], statusCode: Int): GetWeatherOutput =
    GetWeatherOutput(weather, statusCode, degrees)

  implicit val schema: Schema[GetWeatherOutput] = struct(
    string.required[GetWeatherOutput]("weather", _.weather),
    int.optional[GetWeatherOutput]("degrees", _.degrees),
    int
      .field[GetWeatherOutput]("statusCode", _.statusCode)
      .addHints(
        smithy.api.Default(smithy4s.Document.fromDouble(200.0d)),
        smithy.api.HttpResponseCode()
      )
  )(make).withId(id).addHints(hints)
}
