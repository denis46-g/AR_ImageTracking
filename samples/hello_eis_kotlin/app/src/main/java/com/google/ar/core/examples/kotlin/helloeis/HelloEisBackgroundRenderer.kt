/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.kotlin.helloeis

import android.media.Image
import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Coordinates3d
import com.google.ar.core.Frame
import com.google.ar.core.examples.java.common.samplerender.Framebuffer
import com.google.ar.core.examples.java.common.samplerender.Mesh
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.java.common.samplerender.Shader
import com.google.ar.core.examples.java.common.samplerender.Texture
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * This class renders the AR background from camera feed. It creates and hosts the texture given to
 * ARCore to be filled with the camera image.
 */
class HelloEisBackgroundRenderer(render: SampleRender) {
  private val mesh: Mesh
  private val cameraTexCoords =
    ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE_3D).order(ByteOrder.nativeOrder()).asFloatBuffer()
  private val screenCoords =
    ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE_3D).order(ByteOrder.nativeOrder()).asFloatBuffer()
  private val cameraTexCoordsVertexBuffer =
    VertexBuffer(render, /* numberOfEntriesPerVertex= */ 3, null)
  private val screenCoordsVertexBuffer =
    VertexBuffer(render, /* numberOfEntriesPerVertex= */ 3, null)
  private val cameraDepthTexture =
    Texture(
      render,
      Texture.Target.TEXTURE_2D,
      Texture.WrapMode.CLAMP_TO_EDGE,
      /* useMipmaps= */ false
    )
  private var backgroundShader: Shader? = null
  private var occlusionShader: Shader? = null
  /** The camera color texture generated by this object. */
  internal val cameraColorTexture =
    Texture(
      render,
      Texture.Target.TEXTURE_EXTERNAL_OES,
      Texture.WrapMode.CLAMP_TO_EDGE,
      /* useMipmaps= */ false
    )
  private lateinit var depthColorPaletteTexture: Texture
  private var useDepthVisualization = false
  private var useOcclusion = false
  private var aspectRatio = 0f

  /**
   * Allocates and initializes OpenGL resources needed by the background renderer. Must be called
   * during a [SampleRender.Renderer] callback, typically in
   * [SampleRender.Renderer.onSurfaceCreated].
   */
  init {
    // Create a Mesh with three vertex buffers: one for the screen coordinates (normalized device
    // coordinates), one for the camera texture coordinates (to be populated with proper data later
    // before drawing), and one for the virtual scene texture coordinates (unit texture quad)
    val virtualSceneTexCoordsVertexBuffer =
      VertexBuffer(render, /* numberOfEntriesPerVertex= */ 2, VIRTUAL_SCENE_TEX_COORDS_BUFFER)
    val vertexBuffers =
      arrayOf(
        screenCoordsVertexBuffer,
        cameraTexCoordsVertexBuffer,
        virtualSceneTexCoordsVertexBuffer
      )
    mesh = Mesh(render, Mesh.PrimitiveMode.TRIANGLE_STRIP, /* indexBuffer= */ null, vertexBuffers)
  }

  /**
   * Sets whether the background camera image should be replaced with a depth visualization instead.
   * This reloads the corresponding shader code, and must be called on the GL thread.
   */
  @Throws(IOException::class)
  fun setUseDepthVisualization(render: SampleRender, useDepthVisualization: Boolean) {
    backgroundShader?.let {
      if (this.useDepthVisualization == useDepthVisualization) {
        return
      }
      it.close()
      backgroundShader = null
      this.useDepthVisualization = useDepthVisualization
    }
    if (useDepthVisualization) {
      depthColorPaletteTexture =
        Texture.createFromAsset(
          render,
          "models/depth_color_palette.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.LINEAR
        )
      backgroundShader =
        Shader.createFromAssets(
            render,
            "shaders/eis_show_depth_color_visualization.vert",
            "shaders/eis_show_depth_color_visualization.frag",
            /* defines= */ null
          )
          .setTexture("u_CameraDepthTexture", cameraDepthTexture)
          .setTexture("u_ColorMap", depthColorPaletteTexture)
          .setDepthTest(false)
          .setDepthWrite(false)
    } else {
      backgroundShader =
        Shader.createFromAssets(
            render,
            "shaders/eis_show_camera.vert",
            "shaders/eis_show_camera.frag",
            /* defines= */ null
          )
          .setTexture("u_CameraColorTexture", cameraColorTexture)
          .setDepthTest(false)
          .setDepthWrite(false)
    }
  }

  /**
   * Sets whether to use depth for occlusion. This reloads the shader code with new [defines], and
   * must be called on the GL thread.
   */
  @Throws(IOException::class)
  fun setUseOcclusion(render: SampleRender, useOcclusion: Boolean) {
    occlusionShader?.let {
      if (this.useOcclusion == useOcclusion) {
        return
      }
      it.close()
      occlusionShader = null
      this.useOcclusion = useOcclusion
    }
    val defines = mapOf("USE_OCCLUSION" to if (useOcclusion) "1" else "0")
    occlusionShader =
      Shader.createFromAssets(render, "shaders/occlusion.vert", "shaders/occlusion.frag", defines)
        .setDepthTest(false)
        .setDepthWrite(false)
        .setBlend(Shader.BlendFactor.SRC_ALPHA, Shader.BlendFactor.ONE_MINUS_SRC_ALPHA)
    if (useOcclusion) {
      occlusionShader
        ?.setTexture("u_CameraDepthTexture", cameraDepthTexture)
        ?.setFloat("u_DepthAspectRatio", aspectRatio)
    }
  }

  /**
   * Updates the display geometry. This must be called every [frame] before calling either of
   * [EisBackgroundRenderer]'s draw methods.
   *
   * @param frame The current [Frame] as returned by [com.google.ar.core.Session.update].
   */
  fun updateDisplayGeometry(frame: Frame) {
    // If display rotation changed (also includes view size change), we need to re-query the UV
    // coordinates for the screen rect, as they may have changed as well.
    NDC_QUAD_COORDS_BUFFER.rewind()
    frame.transformCoordinates3d(
      Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
      NDC_QUAD_COORDS_BUFFER,
      Coordinates3d.EIS_NORMALIZED_DEVICE_COORDINATES,
      screenCoords
    )
    screenCoordsVertexBuffer.set(screenCoords)

    NDC_QUAD_COORDS_BUFFER.rewind()
    frame.transformCoordinates3d(
      Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
      NDC_QUAD_COORDS_BUFFER,
      Coordinates3d.EIS_TEXTURE_NORMALIZED,
      cameraTexCoords
    )
    cameraTexCoordsVertexBuffer.set(cameraTexCoords)
  }

  /** Update depth texture with [Image] contents. */
  fun updateCameraDepthTexture(image: Image) {
    // SampleRender abstraction leaks here
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTexture.textureId)
    GLES30.glTexImage2D(
      GLES30.GL_TEXTURE_2D,
      0,
      GLES30.GL_RG8,
      image.width,
      image.height,
      0,
      GLES30.GL_RG,
      GLES30.GL_UNSIGNED_BYTE,
      image.planes[0].buffer
    )
    if (useOcclusion) {
      aspectRatio = image.width.toFloat() / image.height.toFloat()
      occlusionShader?.setFloat("u_DepthAspectRatio", aspectRatio)
    }
  }

  /**
   * Draws the AR background image. The image will be drawn such that virtual content rendered with
   * the matrices provided by [com.google.ar.core.Camera.getViewMatrix] and
   * [com.google.ar.core.Camera.getProjectionMatrix] will accurately follow static physical objects.
   */
  fun drawBackground(render: SampleRender) {
    backgroundShader?.let { render.draw(mesh, it) }
  }

  /**
   * Draws the virtual scene. Any objects rendered in the given [Framebuffer] will be drawn given
   * the previously specified [OcclusionMode].
   *
   * Virtual content should be rendered using the matrices provided by
   * [com.google.ar.core.Camera.getViewMatrix] and [com.google.ar.core.Camera.getProjectionMatrix].
   */
  fun drawVirtualScene(
    render: SampleRender,
    virtualSceneFramebuffer: Framebuffer,
    zNear: Float,
    zFar: Float
  ) {
    /**
     * With EIS on, the OpenGL normalized device coordinates (NDC) are expanded for background
     * camera texture rendering. When virtual assets are rendered, using these EIS compensated
     * device coordinates leads to swimming effect since virtual assets are rendered in world space
     * not camera space. Use regular NDC for virtual scene rendering.
     */
    NDC_QUAD_COORDS_BUFFER_3D.rewind()
    screenCoordsVertexBuffer.set(NDC_QUAD_COORDS_BUFFER_3D)
    occlusionShader?.let {
      it.setTexture("u_VirtualSceneColorTexture", virtualSceneFramebuffer.colorTexture)
      if (useOcclusion) {
        it
          .setTexture("u_VirtualSceneDepthTexture", virtualSceneFramebuffer.depthTexture)
          .setFloat("u_ZNear", zNear)
          .setFloat("u_ZFar", zFar)
      }
      render.draw(mesh, it)
    }
  }

  companion object {
    private val TAG = HelloEisBackgroundRenderer::class.java.simpleName
    /** components_per_vertex * number_of_vertices * float_size */
    private const val COORDS_BUFFER_SIZE_2D = 2 * 4 * Float.SIZE_BYTES
    private const val COORDS_BUFFER_SIZE_3D = 3 * 4 * Float.SIZE_BYTES
    private val NDC_QUAD_COORDS_BUFFER =
      ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE_2D)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
          put(
            floatArrayOf(
              /* 0: */
              -1f,
              -1f,
              /* 1: */
              +1f,
              -1f,
              /* 2: */
              -1f,
              +1f,
              /* 3: */
              +1f,
              +1f
            )
          )
        }
    private val NDC_QUAD_COORDS_BUFFER_3D =
      ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE_3D)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
          put(
            floatArrayOf(
              /* 0: */
              -1f,
              -1f,
              0f,
              /* 1: */
              +1f,
              -1f,
              0f,
              /* 2: */
              -1f,
              +1f,
              0f,
              /* 3: */
              +1f,
              +1f,
              0f
            )
          )
        }
    private val VIRTUAL_SCENE_TEX_COORDS_BUFFER =
      ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE_2D)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
          put(
            floatArrayOf(
              /* 0: */
              0f,
              0f,
              /* 1: */
              1f,
              0f,
              /* 2: */
              0f,
              1f,
              /* 3: */
              1f,
              1f
            )
          )
        }
  }
}
