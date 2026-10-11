#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <memory>
#include <vector>
#include <cstring>

namespace {
struct Sampler {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    GLuint program = 0, input = 0, output = 0, framebuffer = 0;
    int width = 0, height = 0;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC clientBuffer = nullptr;
    PFNEGLCREATEIMAGEKHRPROC createImage = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC targetImage = nullptr;
    std::vector<unsigned char> rgba;
    bool current() { return eglMakeCurrent(display, surface, surface, context) == EGL_TRUE; }
    ~Sampler() {
        if (context != EGL_NO_CONTEXT && current()) {
            if (program) glDeleteProgram(program);
            if (input) glDeleteTextures(1, &input);
            if (output) glDeleteTextures(1, &output);
            if (framebuffer) glDeleteFramebuffers(1, &framebuffer);
        }
        if (display != EGL_NO_DISPLAY) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
            // EGL_DEFAULT_DISPLAY is shared with other engines in this process.
            // Destroy only this sampler's objects; terminating it breaks their contexts.
        }
    }
};
GLuint shader(GLenum kind, const char* source) {
    GLuint id = glCreateShader(kind);
    glShaderSource(id, 1, &source, nullptr); glCompileShader(id);
    GLint ok = 0; glGetShaderiv(id, GL_COMPILE_STATUS, &ok);
    if (!ok) { glDeleteShader(id); return 0; }
    return id;
}
std::unique_ptr<Sampler> makeSampler() {
    auto s = std::make_unique<Sampler>();
    s->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (s->display == EGL_NO_DISPLAY || !eglInitialize(s->display, nullptr, nullptr)) return nullptr;
    const char* extensions = eglQueryString(s->display, EGL_EXTENSIONS);
    if (!extensions || !strstr(extensions, "EGL_ANDROID_image_native_buffer")) return nullptr;
    const EGLint attributes[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig config; EGLint count = 0;
    if (!eglChooseConfig(s->display, attributes, &config, 1, &count) || count < 1) return nullptr;
    const EGLint pbuffer[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    s->surface = eglCreatePbufferSurface(s->display, config, pbuffer);
    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    s->context = eglCreateContext(s->display, config, EGL_NO_CONTEXT, contextAttributes);
    if (s->surface == EGL_NO_SURFACE || s->context == EGL_NO_CONTEXT || !s->current()) return nullptr;
    const char* glExtensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    if (!glExtensions || !strstr(glExtensions, "GL_OES_EGL_image_external")) return nullptr;
    s->clientBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    s->createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    s->destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    s->targetImage = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!s->clientBuffer || !s->createImage || !s->destroyImage || !s->targetImage) return nullptr;
    const char* vertex = "attribute vec2 position; void main(){ gl_Position=vec4(position,0.0,1.0); }";
    const char* fragment = R"(#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES source;
uniform vec2 size;
void main() {
    vec2 cell = floor(gl_FragCoord.xy);
    float gray = 0.0;
    for(int y=0;y<4;y++) for(int x=0;x<4;x++) {
        vec2 uv = (cell + (vec2(float(x),float(y)) + 0.5) / 4.0) / size;
        gray += dot(texture2D(source,uv).rgb,vec3(77.0,150.0,29.0)/256.0);
    }
    gl_FragColor=vec4(vec3(gray/16.0),1.0);
})";
    GLuint vs = shader(GL_VERTEX_SHADER, vertex), fs = shader(GL_FRAGMENT_SHADER, fragment);
    if (!vs || !fs) { if (vs) glDeleteShader(vs); if (fs) glDeleteShader(fs); return nullptr; }
    s->program = glCreateProgram(); glAttachShader(s->program, vs); glAttachShader(s->program, fs);
    glBindAttribLocation(s->program, 0, "position"); glLinkProgram(s->program);
    glDeleteShader(vs); glDeleteShader(fs);
    GLint linked = 0; glGetProgramiv(s->program, GL_LINK_STATUS, &linked);
    if (!linked) return nullptr;
    glGenTextures(1, &s->input); glGenTextures(1, &s->output); glGenFramebuffers(1, &s->framebuffer);
    return s;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lmreader_core_vision_GpuMotionSampler_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(makeSampler().release());
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lmreader_core_vision_GpuMotionSampler_nativeSample(JNIEnv* env, jobject, jlong handle,
    jobject javaBuffer, jint width, jint height) {
    auto* s = reinterpret_cast<Sampler*>(handle);
    if (!s || width < 17 || height < 17 || width > 512 || height > 512 || !s->current()) return nullptr;
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, javaBuffer);
    if (!buffer) return nullptr;
    EGLClientBuffer client = s->clientBuffer(buffer);
    const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLImageKHR image = s->createImage(s->display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attributes);
    if (image == EGL_NO_IMAGE_KHR) return nullptr;
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_EXTERNAL_OES, s->input);
    s->targetImage(GL_TEXTURE_EXTERNAL_OES, image);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, s->output);
    if (s->width != width || s->height != height) {
        s->width = width; s->height = height; s->rgba.resize(width * height * 4);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, s->framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s->output, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { s->destroyImage(s->display, image); return nullptr; }
    glViewport(0, 0, width, height); glUseProgram(s->program);
    glUniform1i(glGetUniformLocation(s->program, "source"), 0);
    glUniform2f(glGetUniformLocation(s->program, "size"), static_cast<float>(width), static_cast<float>(height));
    const GLfloat vertices[] = {-1,-1, 1,-1, -1,1, 1,1};
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, vertices);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, s->rgba.data());
    const GLenum error = glGetError();
    s->destroyImage(s->display, image);
    if (error != GL_NO_ERROR) return nullptr;
    std::vector<jbyte> gray(width * height);
    for (size_t i = 0; i < gray.size(); ++i) gray[i] = static_cast<jbyte>(s->rgba[i * 4]);
    jbyteArray result = env->NewByteArray(static_cast<jsize>(gray.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(gray.size()), gray.data());
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_lmreader_core_vision_GpuMotionSampler_nativeClose(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Sampler*>(handle);
}
