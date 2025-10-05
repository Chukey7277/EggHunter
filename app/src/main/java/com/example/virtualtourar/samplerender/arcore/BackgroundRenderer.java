/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.virtualtourar.samplerender.arcore;

import android.media.Image;
import android.opengl.GLES30;

import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.example.virtualtourar.samplerender.Framebuffer;
import com.example.virtualtourar.samplerender.Mesh;
import com.example.virtualtourar.samplerender.SampleRender;
import com.example.virtualtourar.samplerender.Shader;
import com.example.virtualtourar.samplerender.Texture;
import com.example.virtualtourar.samplerender.VertexBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;

/**
 * Renders the AR camera background and composes the scene foreground.
 * Adds CPU-side camera UV scaling so the camera feed can be "zoomed" (cropped) without shader changes.
 */
public class BackgroundRenderer {
  private static final String TAG = BackgroundRenderer.class.getSimpleName();

  // components_per_vertex * number_of_vertices * float_size
  private static final int COORDS_BUFFER_SIZE = 2 * 4 * 4;

  private static final FloatBuffer NDC_QUAD_COORDS_BUFFER =
          ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();

  private static final FloatBuffer VIRTUAL_SCENE_TEX_COORDS_BUFFER =
          ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();

  static {
    NDC_QUAD_COORDS_BUFFER.put(
            new float[] {
                    /*0:*/ -1f, -1f, /*1:*/ +1f, -1f, /*2:*/ -1f, +1f, /*3:*/ +1f, +1f,
            });
    VIRTUAL_SCENE_TEX_COORDS_BUFFER.put(
            new float[] {
                    /*0:*/ 0f, 0f, /*1:*/ 1f, 0f, /*2:*/ 0f, 1f, /*3:*/ 1f, 1f,
            });
  }

  // Raw camera texture coords from ARCore (updated when display geometry changes)
  private final FloatBuffer cameraTexCoords =
          ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();

  // We keep a copy of the ARCore-provided base UVs, then scale around their centroid for zoom
  private final float[] baseCameraUvs = new float[8];    // 4 verts * 2
  private final float[] workingCameraUvs = new float[8]; // after zoom applied
  private boolean texCoordsDirty = true;                 // needs GPU buffer update

  private final Mesh mesh;
  private final VertexBuffer cameraTexCoordsVertexBuffer;
  private Shader backgroundShader;
  private Shader occlusionShader;
  private final Texture cameraDepthTexture;
  private final Texture cameraColorTexture;
  private Texture depthColorPaletteTexture;

  private boolean useDepthVisualization;
  private boolean useOcclusion;
  private float aspectRatio;

  // --- Camera zoom (digicam) ---
  // 1.0 = no zoom. Values >1 crop in. We clamp to [1, 6] by default.
  private float cameraZoom = 1f;
  private static final float CAMERA_ZOOM_MIN = 1f;
  private static final float CAMERA_ZOOM_MAX = 6f;

  /**
   * Allocates and initializes OpenGL resources needed by the background renderer. Must be called
   * during a {@link SampleRender.Renderer} callback, typically in
   * {@link SampleRender.Renderer#onSurfaceCreated(SampleRender)}.
   */
  public BackgroundRenderer(SampleRender render) {
    cameraColorTexture =
            new Texture(
                    render,
                    Texture.Target.TEXTURE_EXTERNAL_OES,
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    /*useMipmaps=*/ false);
    cameraDepthTexture =
            new Texture(
                    render,
                    Texture.Target.TEXTURE_2D,
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    /*useMipmaps=*/ false);

    // Create a Mesh with three vertex buffers: one for the screen coordinates (normalized device
    // coordinates), one for the camera texture coordinates (to be populated with proper data later
    // before drawing), and one for the virtual scene texture coordinates (unit texture quad)
    VertexBuffer screenCoordsVertexBuffer =
            new VertexBuffer(render, /* numberOfEntriesPerVertex=*/ 2, NDC_QUAD_COORDS_BUFFER);
    cameraTexCoordsVertexBuffer =
            new VertexBuffer(render, /*numberOfEntriesPerVertex=*/ 2, /*entries=*/ null);
    VertexBuffer virtualSceneTexCoordsVertexBuffer =
            new VertexBuffer(render, /* numberOfEntriesPerVertex=*/ 2, VIRTUAL_SCENE_TEX_COORDS_BUFFER);
    VertexBuffer[] vertexBuffers = {
            screenCoordsVertexBuffer, cameraTexCoordsVertexBuffer, virtualSceneTexCoordsVertexBuffer,
    };
    mesh =
            new Mesh(render, Mesh.PrimitiveMode.TRIANGLE_STRIP, /*indexBuffer=*/ null, vertexBuffers);
  }

  /**
   * Sets whether the background camera image should be replaced with a depth visualization instead.
   * This reloads the corresponding shader code, and must be called on the GL thread (e.g., from
   * {@link SampleRender.Renderer#onSurfaceCreated(SampleRender)} or the render thread).
   */
  public void setUseDepthVisualization(SampleRender render, boolean useDepthVisualization)
          throws IOException {
    if (backgroundShader != null) {
      if (this.useDepthVisualization == useDepthVisualization) {
        return;
      }
      backgroundShader.close();
      backgroundShader = null;
      this.useDepthVisualization = useDepthVisualization;
    }
    if (useDepthVisualization) {
      depthColorPaletteTexture =
              Texture.createFromAsset(
                      render,
                      "models/depth_color_palette.png",
                      Texture.WrapMode.CLAMP_TO_EDGE,
                      Texture.ColorFormat.LINEAR);
      backgroundShader =
              Shader.createFromAssets(
                              render,
                              "shaders/background_show_depth_color_visualization.vert",
                              "shaders/background_show_depth_color_visualization.frag",
                              /*defines=*/ null)
                      .setTexture("u_CameraDepthTexture", cameraDepthTexture)
                      .setTexture("u_ColorMap", depthColorPaletteTexture)
                      .setDepthTest(false)
                      .setDepthWrite(false);
    } else {
      backgroundShader =
              Shader.createFromAssets(
                              render,
                              "shaders/background_show_camera.vert",
                              "shaders/background_show_camera.frag",
                              /*defines=*/ null)
                      .setTexture("u_CameraColorTexture", cameraColorTexture)
                      .setDepthTest(false)
                      .setDepthWrite(false);
    }
  }

  /**
   * Sets whether to use depth for occlusion. This reloads the shader code with new {@code
   * #define}s, and must be called on the GL thread.
   */
  public void setUseOcclusion(SampleRender render, boolean useOcclusion) throws IOException {
    if (occlusionShader != null) {
      if (this.useOcclusion == useOcclusion) {
        return;
      }
      occlusionShader.close();
      occlusionShader = null;
      this.useOcclusion = useOcclusion;
    }
    HashMap<String, String> defines = new HashMap<>();
    defines.put("USE_OCCLUSION", useOcclusion ? "1" : "0");
    occlusionShader =
            Shader.createFromAssets(render, "shaders/occlusion.vert", "shaders/occlusion.frag", defines)
                    .setDepthTest(false)
                    .setDepthWrite(false)
                    .setBlend(Shader.BlendFactor.SRC_ALPHA, Shader.BlendFactor.ONE_MINUS_SRC_ALPHA);
    if (useOcclusion) {
      occlusionShader
              .setTexture("u_CameraDepthTexture", cameraDepthTexture)
              .setFloat("u_DepthAspectRatio", aspectRatio);
    }
  }

  /**
   * Updates the display geometry. Must be called every frame before draw methods.
   * We also capture the raw UVs so we can apply camera zoom around their centroid.
   *
   * @param frame The current {@link com.google.ar.core.Frame} from {@link com.google.ar.core.Session#update()}.
   */
  public void updateDisplayGeometry(Frame frame) {
    if (frame.hasDisplayGeometryChanged()) {
      // Re-query the UV coordinates for the screen rect.
      cameraTexCoords.rewind();
      frame.transformCoordinates2d(
              Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
              NDC_QUAD_COORDS_BUFFER,
              Coordinates2d.TEXTURE_NORMALIZED,
              cameraTexCoords);

      // Copy into our base array for later scaling
      cameraTexCoords.rewind();
      cameraTexCoords.get(baseCameraUvs, 0, 8);
      texCoordsDirty = true; // geometry changed => need to rebuild with zoom applied
    }
  }

  /** Update depth texture with Image contents. */
  public void updateCameraDepthTexture(Image image) {
    // SampleRender abstraction leaks here
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTexture.getTextureId());
    GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RG8,
            image.getWidth(),
            image.getHeight(),
            0,
            GLES30.GL_RG,
            GLES30.GL_UNSIGNED_BYTE,
            image.getPlanes()[0].getBuffer());
    if (useOcclusion) {
      aspectRatio = (float) image.getWidth() / (float) image.getHeight();
      occlusionShader.setFloat("u_DepthAspectRatio", aspectRatio);
    }
  }

  /**
   * Draws the AR background image (camera or depth viz).
   * Ensures camera UVs reflect any requested camera zoom.
   */
  public void drawBackground(SampleRender render) {
    // If UVs are marked dirty (zoom changed or display geometry changed), rebuild buffer now.
    if (!useDepthVisualization && texCoordsDirty) {
      rebuildCameraTexCoordsWithZoom();
    }
    render.draw(mesh, backgroundShader);
  }

  /**
   * Draws the virtual scene. Any objects rendered in the given {@link Framebuffer} will be drawn
   * given the previously specified occlusion setting.
   *
   * <p>Virtual content should be rendered using the matrices provided by
   * {@link com.google.ar.core.Camera#getViewMatrix(float[], int)} and
   * {@link com.google.ar.core.Camera#getProjectionMatrix(float[], int, float, float)}.
   */
  public void drawVirtualScene(
          SampleRender render, Framebuffer virtualSceneFramebuffer, float zNear, float zFar) {
    occlusionShader.setTexture(
            "u_VirtualSceneColorTexture", virtualSceneFramebuffer.getColorTexture());
    if (useOcclusion) {
      occlusionShader
              .setTexture("u_VirtualSceneDepthTexture", virtualSceneFramebuffer.getDepthTexture())
              .setFloat("u_ZNear", zNear)
              .setFloat("u_ZFar", zFar);
    }
    render.draw(mesh, occlusionShader);
  }

  /** Return the camera color texture generated by this object. */
  public Texture getCameraColorTexture() {
    return cameraColorTexture;
  }

  /** Return the camera depth texture generated by this object. */
  public Texture getCameraDepthTexture() {
    return cameraDepthTexture;
  }

  // ------------------ Camera "digicam" zoom API ------------------

  /**
   * Sets zoom for the camera feed. 1.0 = no zoom. Values >1 crop/zoom in.
   * Call this from your activity (e.g., each frame before drawBackground) whenever the pinch scale changes.
   */
  public void setCameraZoom(float zoom) {
    float z = zoom;
    if (Float.isNaN(z) || Float.isInfinite(z)) return;
    if (z < CAMERA_ZOOM_MIN) z = CAMERA_ZOOM_MIN;
    if (z > CAMERA_ZOOM_MAX) z = CAMERA_ZOOM_MAX;
    if (Math.abs(z - this.cameraZoom) > 1e-4f) {
      this.cameraZoom = z;
      // Mark UVs dirty so they are rebuilt next draw
      texCoordsDirty = true;
    }
  }

  public float getCameraZoom() {
    return cameraZoom;
  }

  /**
   * Applies current cameraZoom by scaling ARCore-provided texture coordinates around their centroid.
   * No shader changes required.
   */
  private void rebuildCameraTexCoordsWithZoom() {
    // If we don't yet have base UVs, nothing to do.
    boolean baseEmpty = true;
    for (int i = 0; i < 8; i++) {
      if (baseCameraUvs[i] != 0f) { baseEmpty = false; break; }
    }
    if (baseEmpty) {
      // Fall back to unit quad until ARCore provides proper UVs.
      float[] fallback = new float[]{0f,0f, 1f,0f, 0f,1f, 1f,1f};
      System.arraycopy(fallback, 0, baseCameraUvs, 0, 8);
    }

    // Zoom factor expressed as how much of the original we keep (s = 1/zoom)
    float s = 1f / Math.max(CAMERA_ZOOM_MIN, Math.min(cameraZoom, CAMERA_ZOOM_MAX));

    // Compute centroid of the quad in texture space (handles rotated/asymmetric UVs)
    float cx = 0f, cy = 0f;
    for (int i = 0; i < 8; i += 2) {
      cx += baseCameraUvs[i];
      cy += baseCameraUvs[i + 1];
    }
    cx *= 0.25f;
    cy *= 0.25f;

    // Scale each vertex about the centroid
    for (int i = 0; i < 8; i += 2) {
      float u = baseCameraUvs[i];
      float v = baseCameraUvs[i + 1];
      float du = (u - cx) * s;
      float dv = (v - cy) * s;
      workingCameraUvs[i]     = cx + du;
      workingCameraUvs[i + 1] = cy + dv;
    }

    // Push to GPU
    FloatBuffer fb =
            ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();
    fb.put(workingCameraUvs);
    fb.rewind();
    cameraTexCoordsVertexBuffer.set(fb);

    texCoordsDirty = false;
  }
}
