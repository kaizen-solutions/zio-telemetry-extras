package weather

import smithy4s.Hints
import smithy4s.Schema
import smithy4s.ShapeId
import smithy4s.ShapeTag
import smithy4s.Smithy4sThrowable
import smithy4s.schema.Schema.constant

final case class CityNotFoundError() extends Smithy4sThrowable

object CityNotFoundError extends ShapeTag.Companion[CityNotFoundError] {
  val id: ShapeId = ShapeId("weather", "CityNotFoundError")

  val hints: Hints = Hints(
    smithy.api.Error.CLIENT.widen,
    smithy.api.HttpError(404),
  ).lazily


  implicit val schema: Schema[CityNotFoundError] = constant(CityNotFoundError()).withId(id).addHints(hints)
}
