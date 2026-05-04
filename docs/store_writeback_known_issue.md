# Store Write-Back Known Issue After PutPartial Fix

## Current Status

The TileLink `PutPartial` assertion has been fixed.

The accelerator no longer fails with:

```text
'A' channel carries PutPartial type which is unexpected using diplomatic parameters

This confirms that the STORE stage no longer generates the unsupported partial-width TileLink write transaction.

Remaining Issue

Although the PutPartial protocol error is resolved, the functional convolution test still shows output mismatches.

Example results:

[Test1_1x1] MISMATCH at [22][0]: hw=0x0000 sw=0x1600
[Test2_3x3] MISMATCH at [26][0]: hw=0x0000 sw=0x1a00

For Test3_5x5, the accelerator prints:

CONFIG success
LOAD complete
COMPUTE complete
STORE complete

However, after STORE complete, the final response from the STORE command is not always observed before the simulation timeout.

Initial Analysis

The PutPartial issue and the output mismatch issue are separate.

The PutPartial issue was caused by the original STORE stage writing one 16-bit output element per memory request. Since the memory interface is 64-bit wide, a 2-byte store generated a TileLink PutPartial transaction, which was not supported by the connected memory path.

The fix changed the STORE stage to pack four 16-bit output elements into one 64-bit store. This avoids the unsupported PutPartial transaction.

The remaining mismatch likely comes from STORE write-back correctness rather than the convolution arithmetic itself. The early output values are correct, but later values may remain zero, suggesting that some output locations are not being written back correctly.

Suspected Cause

The current STORE stage advances the store index after io.mem.req.fire.

However, req.fire only means that the memory request has been accepted by the interface. It does not necessarily guarantee that the store has fully completed in memory.

The design may need additional handling for memory-store completion, retry, or nack behavior.

Next Debug Step

A dedicated STORE diagnostic test should be added.

The test should:

Use a 1x1 identity kernel.
Fill the input matrix with non-zero values.
Initialize the output buffer with a sentinel value.
Run CONFIG, LOAD, COMPUTE, and STORE.
Check whether all 1024 output elements are overwritten correctly.

If some elements remain as the sentinel value or zero, the issue is likely in the STORE write-back path.
