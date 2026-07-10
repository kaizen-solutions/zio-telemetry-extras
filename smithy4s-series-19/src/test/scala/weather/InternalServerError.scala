package weather

import smithy4s.{Hints, Schema, ShapeId, ShapeTag, Smithy4sThrowable}
import smithy4s.schema.Schema.{string, struct}

final case class InternalServerError(reason: Option[String] = None) extends Smithy4sThrowable

object InternalServerError extends ShapeTag.Companion[InternalServerError] {
  val id: ShapeId = ShapeId("weather", "InternalServerError")

  val hints: Hints = Hints(
    smithy.api.Error.SERVER.widen,
    smithy.api.HttpError(500)
  ).lazily

  // constructor using the original order from the spec
  private def make(reason: Option[String]): InternalServerError = InternalServerError(reason)

  implicit val schema: Schema[InternalServerError] = struct(
    string.optional[InternalServerError]("reason", _.reason)
  )(make).withId(id).addHints(hints)
}
