package weather

import smithy4s.{Hints, Schema, ShapeId, ShapeTag}
import smithy4s.schema.Schema.{string, struct}

final case class GetWeatherInput(cityId: CityId, region: String)

object GetWeatherInput extends ShapeTag.Companion[GetWeatherInput] {
  val id: ShapeId = ShapeId("weather", "GetWeatherInput")

  val hints: Hints = Hints(
    smithy.api.Input()
  ).lazily

  // constructor using the original order from the spec
  private def make(cityId: CityId, region: String): GetWeatherInput =
    GetWeatherInput(cityId, region)

  implicit val schema: Schema[GetWeatherInput] = struct(
    CityId.schema.required[GetWeatherInput]("cityId", _.cityId).addHints(smithy.api.HttpLabel()),
    string.required[GetWeatherInput]("region", _.region).addHints(smithy.api.HttpQuery("X-Region"))
  )(make).withId(id).addHints(hints)
}
