plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.5.0"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "1.9.25"
	id("info.solidsoft.pitest") version "1.15.0"
	id("io.gitlab.arturbosch.detekt") version "1.23.6"
}

group = "com.paymentservice"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

extra["testcontainers.version"] = "1.21.4"

dependencyManagement {
	imports {
		mavenBom("org.springframework.modulith:spring-modulith-bom:1.4.1")
	}
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-aop")
	implementation("io.micrometer:micrometer-registry-prometheus")
	implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
	implementation("org.camunda.bpm.springboot:camunda-bpm-spring-boot-starter:7.23.0")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Mutation testing is scoped to the pure money and security core that has fast
// unit tests (state machine, fee rounding, webhook signing). The Spring and
// Testcontainers integration suite is deliberately out of scope: pitest reruns
// the covering tests once per mutant, so a database per mutant would be unusable.
pitest {
	junit5PluginVersion.set("1.2.1")
	pitestVersion.set("1.15.0")
	targetClasses.set(
		listOf(
			"com.paymentservice.payment.PaymentStatus*",
			"com.paymentservice.payment.WebhookSigner*",
			"com.paymentservice.ledger.LedgerService\$Companion",
			"com.paymentservice.shared.Money*",
			"com.paymentservice.shared.MonetaryCurrency",
			"com.paymentservice.settlement.SettlementExtractor",
		)
	)
	targetTests.set(
		listOf(
			"com.paymentservice.payment.PaymentStatusTest",
			"com.paymentservice.payment.WebhookSignerTest",
			"com.paymentservice.ledger.LedgerFeeTest",
			"com.paymentservice.shared.MoneyTest",
			"com.paymentservice.settlement.SettlementExtractorTest",
		)
	)
	threads.set(2)
	useClasspathFile.set(true)
	timestampedReports.set(false)
	// the kotlin compiler injects Intrinsics.checkNotNull* guards on platform
	// type returns; removing them is an equivalent mutant (no behaviour change
	// for valid inputs) that only the commercial arcmutate kotlin plugin filters.
	// excluding the call site keeps the score honest rather than chasing noise.
	avoidCallsTo.set(listOf("kotlin.jvm.internal"))
	mutationThreshold.set(90)
}

// Static analysis runs against a baseline so the existing code is accepted and
// only new violations fail the build, the standard way to adopt detekt on an
// established codebase.
detekt {
	buildUponDefaultConfig = true
	baseline = file("detekt-baseline.xml")
}

// detekt refuses to run unless the kotlin compiler on its own classpath matches
// the exact version it was built against (1.9.23). The project is on 1.9.25, so
// pin the kotlin artifacts of the detekt configuration only, leaving the build's
// own kotlin version untouched.
configurations.matching { it.name == "detekt" }.all {
	resolutionStrategy.eachDependency {
		if (requested.group == "org.jetbrains.kotlin") {
			useVersion("1.9.23")
		}
	}
}
