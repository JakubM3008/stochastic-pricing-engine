# Stage 1: Build the Rust Shared Library
FROM rust:1.80-slim AS rust-build
WORKDIR /app/rust-sim
COPY rust-sim .
RUN cargo build --release

# Stage 2: Build the Java Spring Boot JAR
FROM gradle:8-jdk23 AS java-build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon

# Stage 3: Minimal Runtime
FROM eclipse-temurin:23-jre
WORKDIR /app
COPY --from=java-build /app/build/libs/*.jar app.jar

# Setup the Rust native library path for Java Panama FFM lookup
RUN mkdir -p rust-sim/target/release
COPY --from=rust-build /app/rust-sim/target/release/librust_sim.so rust-sim/target/release/librust_sim.so

EXPOSE 8080
ENTRYPOINT ["java", "--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
