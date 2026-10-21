plugins {
    alias(libs.plugins.lmreader.android.library.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

/**
 * 把导出的 schema 同步成 androidTest 的 assets。
 *
 * `MigrationTestHelper` 只从 assets 里读 `<数据库类名>/<版本>.json`，而 AGP 10 的
 * **库模块**上 `sourceSets.getByName("androidTest").assets` 会抛
 * `DefaultAndroidLibrarySourceSet_Decorated cannot be cast to AndroidLibrarySourceSet`
 * （官方推荐的那行 DSL 在这个版本组合下用不了）。Sync 到默认 assets 目录绕开它，
 * 效果相同：迁移测试能读到 `schemas/` 里导出的 schema。
 */
val syncMigrationSchemas by tasks.registering(Sync::class) {
    from(layout.projectDirectory.dir("schemas"))
    into(layout.projectDirectory.dir("src/androidTest/assets"))
}
syncMigrationSchemas.configure { dependsOn("kspDebugKotlin") }

tasks.matching { it.name.startsWith("pre") && it.name.endsWith("AndroidTestBuild") }
    .configureEach { dependsOn(syncMigrationSchemas) }

dependencies {
    api(project(":core:model"))
    // 复用 core:index 的自然序键：卡片排序必须与章节排序用同一套规则，
    // 否则同一部作品在列表里的位置与它第一章的顺序会互相矛盾（开发文档 1.3）。
    implementation(project(":core:index"))
    implementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
