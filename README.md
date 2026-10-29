# ZIO Telemetry Extras

OpenTelemetry integrations built for the Scala ecosystem using [zio-telemetry](https://github.com/zio/zio-telemetry)

[![MvnRepository](https://badges.mvnrepository.com/badge/io.kaizen-solutions/zio-telemetry-extras-http4s-metrics/badge.svg?label=MvnRepository)](https://mvnrepository.com/artifact/io.kaizen-solutions/zio-telemetry-extras-http4s-metrics)

All modules are published for Scala 2.13 and Scala 3.

```scala
val zioTelemetryExtrasVersion = "<latest version>"
```

## http4s

The http4s module provides client and server middleware for `http4s` applications using `ZIO`.

```scala
libraryDependencies += "io.kaizen-solutions" %% "zio-telemetry-extras-http4s" % zioTelemetryExtrasVersion
```

## http4s-metrics

The http4s-metrics module provides the `MetricOps` abstraction built on ZIO metrics needed by (client and server) http4s Metrics middleware

```scala
libraryDependencies += "io.kaizen-solutions" %% "zio-telemetry-extras-http4s-metrics" % zioTelemetryExtrasVersion
```

## sttp4-metrics

The sttp4-metrics module provides `ZioMetricsSttpClient`, a wrapper around an sttp4 `SttpClient` backend that records ZIO metrics for every request.

```scala
libraryDependencies += "io.kaizen-solutions" %% "zio-telemetry-extras-sttp4-metrics" % zioTelemetryExtrasVersion
```

## propagators

`zio-telemetry` only ships a W3C Trace Context propagator (`TraceContextPropagator.default`). The propagators module provides ready-made `TraceContextPropagator` instances for the other common wire formats, along with combinators for composing them.

```scala
libraryDependencies += "io.kaizen-solutions" %% "zio-telemetry-extras-propagators" % zioTelemetryExtrasVersion
```

## smithy4s (0.18.x and 0.19.x)

The smithy4s module provides client endpoint middleware and server endpoint middleware for `smithy4s` applications using `ZIO`.

- Server endpoint middleware (dependent on http4s tracing) for tracing to enrich an existing span with smithy4s attributes
- Server endpoint middleware (standalone) for tracing
- Client endpoint middleware (standalone) for tracing
- Server endpoint middleware for metrics
- Client endpoint middleware for metrics

```scala
// smithy4s 0.18.x
libraryDependencies += "io.kaizen-solutions" %% "zio-telemetry-extras-smithy4s-series-18" % zioTelemetryExtrasVersion

// smithy4s 0.19.x
libraryDependencies += "io.kaizen-solutions" %% "zio-telemetry-extras-smithy4s-series-19" % zioTelemetryExtrasVersion
```

## natchez-bridge

The natchez-bridge module provides an implementation of `natchez.Trace[RIO[R, *]]` using `zio.telemetry.opentelemetry.tracing.Tracing` allowing for seamless interop between the two.

```scala
libraryDependencies += "io.kaizen-solutions" %% "zio-telemetry-extras-natchez" % zioTelemetryExtrasVersion
```
