plugins {
    id("java-library")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    compileOnly(files("/home/satya/Android/Sdk/platforms/android-36/android.jar"))
}
