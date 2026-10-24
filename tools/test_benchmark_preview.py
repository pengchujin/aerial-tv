import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('benchmark', Path(__file__).with_name('benchmark-preview.py'))
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)

class FrameParsingTests(unittest.TestCase):
    def test_named_columns_and_invalid_fences(self):
        data = 'Flags,IntendedVsync,FrameCompleted,SwapBuffers,GpuCompleted,\n'
        data += '0,1000000000,1020000000,1010000000,1015000000,\n'
        data += '1,1000000000,1020000000,0,0,\n'
        data += '0,1000000000,9223372036854775807,0,0,\n'
        data += '0,1000000000,990000000,0,0,\n'
        self.assertEqual(bench.frames(data), [{'flags':0, 'frame_ms':20, 'swap_to_gpu_ms':5}])

    def test_vendor_flag_is_preserved_and_known_flags_excluded(self):
        data = 'Flags,IntendedVsync,FrameCompleted\n16,10000000,20000000\n17,10000000,20000000\n'
        self.assertEqual(bench.frames(data), [{'flags': 16, 'frame_ms': 10, 'swap_to_gpu_ms': None}])
        self.assertEqual(bench.summarize(bench.frames(data))['flags'], {16: 1})

    def test_platform_uses_global_not_duplicate_window_counters(self):
        data = 'Total frames rendered: 20\nJanky frames: 2 (10%)\n95th percentile: 32ms\nTotal frames rendered: 10\n'
        self.assertEqual(bench.platform_stats(data)['frames'], 20)
        self.assertEqual(bench.platform_stats(data)['p95_ms'], 32)

    def test_missing_gpu_field_and_small_samples(self):
        self.assertEqual(bench.percentile([10, 20], 95), 19.5)
        self.assertIsNone(bench.percentile([], 95))
        result = bench.frames('Flags,FrameCompleted,IntendedVsync\n0,20000000,10000000\n')
        self.assertEqual(result[0]['frame_ms'], 10)
        self.assertIsNone(bench.summarize([])['frame_p95_ms'])

if __name__ == '__main__': unittest.main()
