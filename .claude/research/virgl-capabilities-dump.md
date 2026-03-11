# VirGL Capability Dump — Samsung Tab S10 Ultra

**Date**: 2026-03-09 (Session 49)
**Device**: Samsung Tab S10 Ultra, Immortalis-G720 MC12 (MediaTek Dimensity 9300+)

## Software Versions

| Component | Version | Location |
|-----------|---------|----------|
| virglrenderer-android | 1.3.0 | Termux host |
| angle-android | 2.1.24923-f09a19ce-2 | Termux host |
| Mesa (proot) | 25.2.8-0ubuntu0.25.10.1 | Ubuntu proot |
| mesa-utils | 9.0.0-2 | Ubuntu proot |

## GL Context (with MESA_GL_VERSION_OVERRIDE=4.5COMPAT)

```
GL_RENDERER = virgl (ANGLE (ARM, Mali-G720-Immortalis MC12, OpenGL ES 3.2...)
GL_VERSION  = 4.5 (Compatibility Profile) Mesa 25.2.8-0ubuntu0.25.10.1
GL_VENDOR   = Mesa
```

## Critical Extension Check

| Extension | Present | Impact |
|-----------|---------|--------|
| **GL_ARB_clip_control** | **NO** | Reversed-Z depth buffer BROKEN — glClipControl is a no-op |
| GL_EXT_texture_array | YES | sampler2DArray works |
| GL_ARB_depth_buffer_float | YES | GL_DEPTH_COMPONENT32F works |
| GL_ARB_framebuffer_object | YES | FBO rendering works |
| GL_EXT_framebuffer_blit | YES | FBO blit works |
| GL_EXT_framebuffer_multisample | YES | MSAA available |
| GL_ARB_depth_texture | YES | Depth textures work |
| GL_NV_packed_depth_stencil | YES | Packed depth+stencil |
| GL_EXT_packed_depth_stencil | YES | Packed depth+stencil |
| GL_AMD_conservative_depth | YES | Conservative depth optimization |
| GL_NV_copy_depth_to_color | YES | Depth buffer readback |
| GL_EXT_texture_storage | YES | Immutable textures |
| GL_NV_copy_image | YES | Texture copies |

## Total Extensions: 164

## Key Extensions (filtered from full dump)

```
GL_IBM_rasterpos_clip
GL_ARB_framebuffer_sRGB
GL_EXT_framebuffer_sRGB
GL_EXT_framebuffer_object
GL_NV_packed_depth_stencil
GL_ARB_depth_texture
GL_NV_copy_depth_to_color
GL_ARB_framebuffer_object
GL_EXT_framebuffer_blit
GL_EXT_framebuffer_multisample
GL_EXT_packed_depth_stencil
GL_EXT_texture_array
GL_OES_EGL_image
GL_ARB_depth_buffer_float
GL_AMD_conservative_depth
GL_EXT_texture_storage
GL_NV_copy_image
```

## Root Cause: Dark Rendering

RuneLite GPU plugin uses **reversed-Z depth buffer**:
- `glDepthFunc(GL_GREATER)` + `glClearDepth(0)`
- Requires `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` (from GL_ARB_clip_control)
- Without clip_control: NDC range stays [-1,+1], depth test fails for most geometry
- `MESA_NO_ERROR=1` silently swallows the glClipControl error

## GLX Extensions

```
GLX_ARB_create_context GLX_ARB_create_context_no_error GLX_ARB_create_context_profile
GLX_ARB_fbconfig_float GLX_ARB_framebuffer_sRGB GLX_ARB_get_proc_address GLX_ARB_multisample
GLX_EXT_create_context_es2_profile GLX_EXT_create_context_es_profile
GLX_EXT_fbconfig_packed_float GLX_EXT_framebuffer_sRGB GLX_EXT_no_config_context
GLX_EXT_texture_from_pixmap GLX_EXT_visual_info GLX_EXT_visual_rating
GLX_MESA_copy_sub_buffer GLX_MESA_gl_interop GLX_MESA_query_renderer
GLX_SGIS_multisample GLX_SGIX_fbconfig GLX_SGIX_pbuffer GLX_SGIX_visual_select_group
GLX_SGI_make_current_read
```

## Known Limitations

1. `glxinfo` crashes (XGetImage BadMatch) — use `glxgears -info` instead
2. `glxgears` works via XPutImage path
3. `noperspective` interpolation — not confirmed (extension not in filtered list, needs broader check)
4. VirGL GLSL native level is 130 (overridden to 330 via env var)
