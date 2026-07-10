package weather

import smithy4s.Hints
import smithy4s.Schema
import smithy4s.ShapeId
import smithy4s.ShapeTag
import smithy4s.schema.Schema.struct

final case class CreateCityOutput(cityId: CityId)

object CreateCityOutput extends ShapeTag.Companion[CreateCityOutput] {
  val id: ShapeId = ShapeId("weather", "CreateCityOutput")

  val hints: Hints = Hints(
    smithy.api.Output(),
  ).lazily

  // constructor using the original order from the spec
  private def make(cityId: CityId): CreateCityOutput = CreateCityOutput(cityId)

  implicit val schema: Schema[CreateCityOutput] = struct(
    CityId.schema.required[CreateCityOutput]("cityId", _.cityId),
  )(make).withId(id).addHints(hints)
}
