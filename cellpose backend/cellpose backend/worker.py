import argparse
import os
import cv2
import json
import sys
import logging
import numpy as np
import base64
from io import BytesIO
from PIL import Image
from pathlib import Path
import platform
import shutil
import tempfile

# --- LOGGING SETUP ---
logging.basicConfig(
    stream=sys.stderr,
    level=logging.INFO,
    format='%(asctime)s | %(levelname)s | %(message)s'
)
logger = logging.getLogger(__name__)

# --- DYNAMIC PATHS ---
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MODELS_DIR = os.path.join(BASE_DIR, "models")
CELLPOSE_31_DIR = os.path.join(MODELS_DIR, "Cellpose 3.1")
CELLPOSE_SAM_DIR = os.path.join(MODELS_DIR, "CellposeSAM")


def get_short_path_name(long_path):
    """
    Convert Windows long path with spaces to short path (8.3 format).
    This fixes PyTorch file reader issues with paths containing spaces.
    Returns the original path on non-Windows systems or if conversion fails.
    """
    if platform.system() != 'Windows':
        return long_path
    
    try:
        import ctypes
        from ctypes import wintypes
        
        _GetShortPathNameW = ctypes.windll.kernel32.GetShortPathNameW
        _GetShortPathNameW.argtypes = [wintypes.LPCWSTR, wintypes.LPWSTR, wintypes.DWORD]
        _GetShortPathNameW.restype = wintypes.DWORD
        
        output_buf_size = 0
        while True:
            output_buf = ctypes.create_unicode_buffer(output_buf_size)
            needed = _GetShortPathNameW(long_path, output_buf, output_buf_size)
            if output_buf_size >= needed:
                short_path = output_buf.value
                logger.info(f"🔄 Converted path: {long_path} -> {short_path}")
                return short_path
            else:
                output_buf_size = needed
    except Exception as e:
        logger.warning(f"⚠️ Could not convert to short path: {e}. Using original path.")
        return long_path


def main():
    logger.info("="*60)
    logger.info("CELLPOSE WORKER STARTED")
    logger.info(f"Command line arguments: {sys.argv}")
    logger.info("="*60)
    
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", required=True)
    parser.add_argument("--model_type", required=True, choices=["Cellpose3.1", "CellposeSAM"])
    parser.add_argument("--model_name", required=True)
    parser.add_argument("--diameter", type=float, default=0.0)
    # Channels should be passed as comma-separated indices, e.g., "0,2" or "1"
    parser.add_argument("--channels", default="0,0")
    parser.add_argument("--use_gpu", action="store_true", help="Enable GPU usage")
    parser.add_argument("--batch_size", type=int, default=64, help="Batch size for processing")
    parser.add_argument("--resample", action="store_true", help="Run dynamics at original image size")
    parser.add_argument("--no_normalize", action="store_true", help="Disable normalization")
    parser.add_argument("--flow_threshold", type=float, default=0.4, help="Flow error threshold")
    parser.add_argument("--cellprob_threshold", type=float, default=0.0, help="Cell probability threshold")
    parser.add_argument("--percentile_low", type=float, default=1.0, help="Lower percentile for normalization")
    parser.add_argument("--percentile_high", type=float, default=99.0, help="Upper percentile for normalization")
    parser.add_argument("--tile_norm", type=int, default=0, help="Tile normalization block size")

    args = parser.parse_args()
    
    logger.info(f"📋 Parsed arguments:")
    logger.info(f"   Image path: {args.image}")
    logger.info(f"   Model type: {args.model_type}")
    logger.info(f"   Model name: {args.model_name}")
    logger.info(f"   Diameter: {args.diameter}")
    logger.info(f"   Channels: {args.channels}")
    logger.info(f"   GPU: {args.use_gpu}")

    # 1. DETERMINE MODEL PATH BASED ON TYPE
    if args.model_type == "Cellpose3.1":
        model_dir = CELLPOSE_31_DIR
    else:  # CellposeSAM
        model_dir = CELLPOSE_SAM_DIR
    
    model_path = os.path.join(model_dir, args.model_name)
    
    # Normalize path to absolute path (handles Windows paths better)
    model_path = os.path.abspath(model_path)
    
    # Fix for Windows paths with spaces - PyTorch C++ loader can't handle them
    # Create a temporary copy without spaces in the path
    temp_model_path = None
    if platform.system() == 'Windows' and ' ' in model_path and args.model_name not in ['cyto3', 'cpsam']:
        try:
            # Create temp file in a directory without spaces
            temp_dir = tempfile.gettempdir()  # Usually C:\Users\user\AppData\Local\Temp
            temp_model_path = os.path.join(temp_dir, f"cellpose_model_{os.getpid()}.tmp")
            logger.info(f"🔄 Creating temporary model copy to avoid path space issue:")
            logger.info(f"   Original: {model_path}")
            logger.info(f"   Temp: {temp_model_path}")
            shutil.copy2(model_path, temp_model_path)
            model_path = temp_model_path
        except Exception as e:
            logger.warning(f"⚠️ Could not create temp copy: {e}. Using original path.")
            # Convert to forward slashes as fallback
            model_path = model_path.replace('\\', '/')
    elif platform.system() == 'Windows':
        # Even without spaces, use forward slashes on Windows for PyTorch
        model_path = model_path.replace('\\', '/')
    
    logger.info(f"📍 Model path: {model_path}")
    
    # 2. VERIFY MODEL EXISTS (skip for built-in models)
    if args.model_name not in ['cyto3', 'cpsam']:
        if not os.path.exists(model_path):
            logger.error(f"❌ CRITICAL ERROR: Model file not found at: {model_path}")
            print(json.dumps({"status": "error", "message": f"Model file missing: {model_path}"}))
            return
        
        logger.info(f"✓ Model file exists, size: {os.path.getsize(model_path)} bytes")

    try:
        from cellpose import models, utils

        # 2. LOAD IMAGE
        # Fix Windows paths with spaces for image path too
        image_path = args.image
        if platform.system() == 'Windows':
            image_path = os.path.abspath(image_path).replace('\\', '/')
        
        logger.info(f"📂 Attempting to load image from: {image_path}")
        logger.info(f"   File exists: {os.path.exists(image_path)}")
        if os.path.exists(image_path):
            logger.info(f"   File size: {os.path.getsize(image_path)} bytes")
            logger.info(f"   Absolute path: {os.path.abspath(image_path)}")
        
        # Note: cv2 loads as BGR. Cellpose generally expects RGB.
        img = cv2.imread(image_path, cv2.IMREAD_UNCHANGED)
        if img is None:
            # Try alternative loading method using PIL
            logger.warning("⚠️ cv2.imread failed, trying PIL.Image.open...")
            try:
                pil_img = Image.open(image_path)
                img = np.array(pil_img)
                logger.info(f"✓ Successfully loaded image with PIL: shape {img.shape}")
            except Exception as pil_error:
                logger.error(f"❌ PIL also failed: {pil_error}")
                raise ValueError(f"Could not read image file: {image_path}\nFile exists: {os.path.exists(image_path)}\nError: cv2.imread returned None")

        # Ensure image has 3 dimensions (H, W, C) if it's color
        # If grayscale (H, W), add channel dim -> (H, W, 1)
        if img.ndim == 2:
            img = img[:, :, np.newaxis]
        elif img.ndim == 3:
            # Convert BGR to RGB for consistency with Cellpose training
            img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)

        model = None
        logger.info(f"🚀 Initializing model: {args.model_type}/{args.model_name} | GPU: {args.use_gpu}")
        
        # 3. INITIALIZE MODELS
        if args.model_type == "Cellpose3.1":
            # Cellpose 3.1 models
            if args.model_name == 'cyto3':
                model = models.Cellpose(gpu=args.use_gpu, model_type='cyto3')
            else:
                # Custom Cellpose 3.1 model
                logger.info(f"📂 Loading custom Cellpose 3.1 weights from: {model_path}")
                model = models.CellposeModel(gpu=args.use_gpu, pretrained_model=model_path)
        
        elif args.model_type == "CellposeSAM":
            # CellposeSAM models
            if args.model_name == 'cpsam':
                model = models.CellposeModel(gpu=args.use_gpu, model_type='cpsam')
            else:
                # Custom SAM model
                logger.info(f"📂 Loading custom SAM weights from: {model_path}")
                model = models.CellposeModel(gpu=args.use_gpu, model_type='cpsam', pretrained_model=model_path)

        if model is None:
            raise ValueError(f"Unknown model: {args.model_type}/{args.model_name}")

        # 4. RUN INFERENCE (Logic splits based on version)
        logger.info("⚡ Starting inference...")

        # Parse channel string "0,2" -> [0, 2]
        user_channels = [int(c) for c in args.channels.split(',') if c.strip().isdigit()]

        # === LOGIC A: CELLPOSE SAM (V4) ===
        # SAM expects the relevant channels to be moved to indices 0,1,2...
        # and does NOT take a 'channels' argument in eval().
        if args.model_type == "CellposeSAM":

            # Create a blank container of the same shape
            img_input = np.zeros_like(img)

            # "Pack" the selected channels into the front of the array
            # Example: If user selects [2, 1] (Red and Green),
            # img_input channel 0 becomes old channel 2
            # img_input channel 1 becomes old channel 1
            # img_input channel 2 remains 0
            if len(user_channels) > 0:
                # Safety check for dimensions
                valid_channels = []
                s = set()
                for x in user_channels:
                    if x not in s:
                        s.add(x)
                        valid_channels.append(x)

                valid_channels = [c for c in valid_channels if c < img.shape[-1]]
                if len(valid_channels) != len(user_channels):
                    logger.warning(f"⚠️ Some requested channels were out of bounds for image with shape {img.shape}")

                img_input[:, :, :len(valid_channels)] = img[:, :, valid_channels]
            else:
                # If no channels specified/valid, pass original (or grayscale)
                img_input = img

            # Call Eval WITHOUT 'channels' arg
            # Build normalize parameter
            if args.no_normalize:
                normalize_param = False
            else:
                normalize_param = {
                    "percentile": [args.percentile_low, args.percentile_high],
                    "tile_norm_blocksize": args.tile_norm
                }
            
            masks, flows, styles = model.eval(
                img_input,
                diameter=args.diameter if args.diameter > 0 else None,
                batch_size=args.batch_size,
                resample=args.resample,
                normalize=normalize_param,
                flow_threshold=args.flow_threshold,
                cellprob_threshold=args.cellprob_threshold
            )[:3]

        # === LOGIC B: CELLPOSE V3 (Standard) ===
        # V3 expects the original image + a 'channels=[cyto, nuc]' list
        else:
            # Standard Cellpose usually expects exactly 2 values [cyto, nucleus]
            # We pad with 0 if only 1 is given, or slice to 2 if too many.
            chan_arg = user_channels + [0, 0]  # Pad with defaults
            chan_arg = chan_arg[:2]  # Take first two

            # Build normalize parameter
            if args.no_normalize:
                normalize_param = False
            else:
                normalize_param = {
                    "percentile": [args.percentile_low, args.percentile_high],
                    "tile_norm_blocksize": args.tile_norm
                }

            masks, flows, styles = model.eval(
                img,
                diameter=args.diameter if args.diameter > 0 else None,
                channels=chan_arg,
                batch_size=args.batch_size,
                resample=args.resample,
                normalize=normalize_param,
                flow_threshold=args.flow_threshold,
                cellprob_threshold=args.cellprob_threshold
            )[:3]

        logger.info("✅ Inference complete.")

        # 5. FORMAT OUTPUT - Convert masks to colored image
        # Create a colored mask image where each cell has a unique color
        h, w = masks.shape
        colored_mask = np.zeros((h, w, 3), dtype=np.uint8)
        
        # Generate distinct colors for each cell
        unique_ids = np.unique(masks)
        for cell_id in unique_ids:
            if cell_id == 0:  # Skip background
                continue
            # Use golden angle for distinct colors (OpenCV HSV uses 0-179 for hue, not 0-360)
            hue = int((cell_id * 137.508) % 180)
            # Convert HSV to RGB
            color = np.array([hue, int(0.8 * 255), int(0.9 * 255)], dtype=np.uint8)
            color_rgb = cv2.cvtColor(np.uint8([[color]]), cv2.COLOR_HSV2RGB)[0][0]
            colored_mask[masks == cell_id] = color_rgb
        
        # Convert to PIL Image
        pil_mask = Image.fromarray(colored_mask, mode='RGB')
        
        # Encode as base64 PNG
        buffered = BytesIO()
        pil_mask.save(buffered, format="PNG")
        mask_b64 = base64.b64encode(buffered.getvalue()).decode('utf-8')
        
        # Also include outline data for compatibility
        outlines = utils.outlines_list(masks)
        results = []
        for outline in outlines:
            roi_coords = ",".join([f"{p[0]},{p[1]}" for p in outline])
            results.append(roi_coords)

        output_data = {
            "mask_image": mask_b64,
            "outlines": results,
            "num_cells": len(outlines)
        }
        
        print(json.dumps({"status": "success", "data": json.dumps(output_data)}))

    except Exception as e:
        logger.error(f"💥 Error occurred: {e}", exc_info=True)
        print(json.dumps({"status": "error", "message": str(e)}))
    
    finally:
        # Clean up temporary model file if created
        if temp_model_path and os.path.exists(temp_model_path):
            try:
                os.remove(temp_model_path)
                logger.info(f"🗑️ Cleaned up temporary model file: {temp_model_path}")
            except Exception as cleanup_error:
                logger.warning(f"⚠️ Could not remove temp file: {cleanup_error}")


if __name__ == "__main__":
    main()