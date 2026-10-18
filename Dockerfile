# Build stage: compile with a JDK
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY src/main ./src/main
RUN mkdir out && javac --release 17 -d out $(find src/main -name '*.java')

# Runtime stage: JRE only, non-root
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/out ./out
RUN useradd --system --uid 10001 app
USER app
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-cp", "out", "com.authswitch.demo.ApiServer"]
