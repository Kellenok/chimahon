from PIL import Image
import os
import time
import numpy as np

CHUNK_HEIGHT_LIMIT = 3000
CHUNK_BOUNDARY_SEARCH_RANGE = 600
CHUNK_BOUNDARY_LINE_MIN_HEIGHT = 5
LOW_VARIANCE_THRESHOLD = 150
EDGE_THRESHOLD = 30
OVERLAP = 96

def compute_row_variance(img_arr, y):
    row = img_arr[y, :, :]
    mean = row.mean(axis=0)
    variance = ((row - mean) ** 2).sum(axis=1).mean()
    return variance

def compute_edge_score(img_arr, y):
    """Average horizontal gradient (difference between adjacent pixels)"""
    row = img_arr[y, :, :]
    diff = np.abs(row[:, 1:] - row[:, :-1]).sum(axis=1)
    return diff.mean()

def find_cuts_variance(img_arr, height, width):
    cuts = []
    current_y = 0
    while current_y < height:
        remaining = height - current_y
        target = min(CHUNK_HEIGHT_LIMIT, remaining)
        if target == 0:
            break
        if remaining <= CHUNK_HEIGHT_LIMIT:
            if remaining > OVERLAP * 2:
                cuts.append(current_y)
            break

        target_y = current_y + target
        search_start = max(0, target_y - CHUNK_BOUNDARY_SEARCH_RANGE)
        search_end = min(height - CHUNK_BOUNDARY_LINE_MIN_HEIGHT, target_y + CHUNK_BOUNDARY_SEARCH_RANGE)

        best_y = None
        best_score = float('inf')

        for y in range(search_start, search_end - CHUNK_BOUNDARY_LINE_MIN_HEIGHT + 2):
            var = compute_row_variance(img_arr, y)
            if var < LOW_VARIANCE_THRESHOLD:
                consecutive = 1
                for cy in range(y + 1, min(y + CHUNK_BOUNDARY_LINE_MIN_HEIGHT, search_end + 1)):
                    if compute_row_variance(img_arr, cy) >= LOW_VARIANCE_THRESHOLD:
                        break
                    consecutive += 1
                if consecutive >= CHUNK_BOUNDARY_LINE_MIN_HEIGHT:
                    dist = abs(y - target_y)
                    if dist < best_score:
                        best_score = dist
                        best_y = y

        actual_height = (best_y - current_y) if best_y is not None else target
        cuts.append(current_y)
        current_y += actual_height - OVERLAP
    return cuts

def find_cuts_edge(img_arr, height, width):
    # Precompute edge scores
    edge_scores = np.array([compute_edge_score(img_arr, y) for y in range(height)])

    cuts = []
    current_y = 0
    while current_y < height:
        remaining = height - current_y
        target = min(CHUNK_HEIGHT_LIMIT, remaining)
        if target == 0:
            break
        if remaining <= CHUNK_HEIGHT_LIMIT:
            if remaining > OVERLAP * 2:
                cuts.append(current_y)
            break

        target_y = current_y + target
        search_start = max(0, target_y - CHUNK_BOUNDARY_SEARCH_RANGE)
        search_end = min(height - CHUNK_BOUNDARY_LINE_MIN_HEIGHT, target_y + CHUNK_BOUNDARY_SEARCH_RANGE)

        best_y = None
        best_score = float('inf')

        for y in range(search_start, search_end - CHUNK_BOUNDARY_LINE_MIN_HEIGHT + 2):
            scores = edge_scores[y:y + CHUNK_BOUNDARY_LINE_MIN_HEIGHT]
            if (scores < EDGE_THRESHOLD).all():
                dist = abs(y - target_y)
                if dist < best_score:
                    best_score = dist
                    best_y = y

        actual_height = (best_y - current_y) if best_y is not None else target
        cuts.append(current_y)
        current_y += actual_height - OVERLAP
    return cuts

def main():
    test_dir = r"G:\Downloads\test"
    files = sorted([f for f in os.listdir(test_dir) if f.endswith('.webp')])

    print("=== CHUNKING BENCHMARK ===")
    print(f"Images: {files}\n")

    for fname in files:
        path = os.path.join(test_dir, fname)
        img = Image.open(path).convert('RGB')
        arr = np.array(img)
        h, w, _ = arr.shape

        print(f"--- {fname} ({w}x{h}) ---")

        t0 = time.time()
        var_cuts = find_cuts_variance(arr, h, w)
        var_time = (time.time() - t0) * 1000

        t0 = time.time()
        edge_cuts = find_cuts_edge(arr, h, w)
        edge_time = (time.time() - t0) * 1000

        # Compute chunk sizes
        var_sizes = []
        for i in range(len(var_cuts)):
            end = var_cuts[i+1] if i+1 < len(var_cuts) else h
            var_sizes.append(end - var_cuts[i])

        edge_sizes = []
        for i in range(len(edge_cuts)):
            end = edge_cuts[i+1] if i+1 < len(edge_cuts) else h
            edge_sizes.append(end - edge_cuts[i])

        print(f"  Variance: {len(var_cuts)} chunks in {var_time:.0f}ms, sizes: {var_sizes}")
        print(f"  Edge:     {len(edge_cuts)} chunks in {edge_time:.0f}ms, sizes: {edge_sizes}")

        # Quality: variance at cut points
        print("  Cut quality (lower = cleaner):")
        for cut in var_cuts:
            v = compute_row_variance(arr, cut)
            print(f"    Variance cut@{cut}: var={v:.1f}")
        for cut in edge_cuts:
            e = compute_edge_score(arr, cut)
            print(f"    Edge     cut@{cut}: edge={e:.1f}")
        print()

if __name__ == '__main__':
    main()
