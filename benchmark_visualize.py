from PIL import Image, ImageDraw
import os
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
    out_dir = r"G:\Downloads\test\benchmark_output"
    os.makedirs(out_dir, exist_ok=True)

    files = sorted([f for f in os.listdir(test_dir) if f.endswith('.webp')])

    for fname in files:
        path = os.path.join(test_dir, fname)
        img = Image.open(path).convert('RGB')
        arr = np.array(img)
        h, w, _ = arr.shape

        var_cuts = find_cuts_variance(arr, h, w)
        edge_cuts = find_cuts_edge(arr, h, w)

        # Draw variance cuts (red lines)
        img_var = img.copy()
        draw_var = ImageDraw.Draw(img_var)
        for cut in var_cuts:
            draw_var.line([(0, cut), (w, cut)], fill='red', width=3)
        img_var.save(os.path.join(out_dir, f"{fname}_variance.png"))

        # Draw edge cuts (green lines)
        img_edge = img.copy()
        draw_edge = ImageDraw.Draw(img_edge)
        for cut in edge_cuts:
            draw_edge.line([(0, cut), (w, cut)], fill='lime', width=3)
        img_edge.save(os.path.join(out_dir, f"{fname}_edge.png"))

        # Draw both (red = variance, green = edge)
        img_both = img.copy()
        draw_both = ImageDraw.Draw(img_both)
        for cut in var_cuts:
            draw_both.line([(0, cut), (w // 2, cut)], fill='red', width=3)
        for cut in edge_cuts:
            draw_both.line([(w // 2, cut), (w, cut)], fill='lime', width=3)
        # Legend
        draw_both.rectangle([(10, 10), (200, 50)], fill=(0, 0, 0, 180))
        draw_both.text((20, 15), "RED = Variance  GREEN = Edge", fill='white')
        img_both.save(os.path.join(out_dir, f"{fname}_both.png"))

        print(f"Saved: {fname}")
        print(f"  Variance cuts: {var_cuts}")
        print(f"  Edge cuts:     {edge_cuts}")
        print()

    print(f"\nAll images saved to: {out_dir}")

if __name__ == '__main__':
    main()
