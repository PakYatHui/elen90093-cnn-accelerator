// =============================================================================
// conv_test.c
// ELEN90093 CNN Convolution Accelerator - Functional Test
//
// Tests 1x1, 3x3, and 5x5 convolution by comparing accelerator output
// against a software reference implementation.
//
// Fixed-point format: 8.8 signed (1 sign bit, 7 integer bits, 8 fraction bits)
// All matrices are 32x32 elements of int16_t.
// =============================================================================

#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include "rocc.h"

// =============================================================================
// Hardware parameters (must match ConvAccelerator.scala)
// =============================================================================
#define INPUT_SIZE   32
#define INPUT_ELEMS  (INPUT_SIZE * INPUT_SIZE)   // 1024

// funct7 encodings (must match ConvAccelerator.scala)
#define FUNCT_CONFIG  0
#define FUNCT_LOAD    1
#define FUNCT_COMPUTE 2
#define FUNCT_STORE   3

// RoCC uses custom0 opcode
#define ROCC_X 0

// =============================================================================
// Fixed-point helpers (8.8 format)
// =============================================================================
// Convert a double to 8.8 fixed-point int16_t


//这部分是宏定义，负责将将整数“转换”成8.8,之后再转换回来变成double
#define TO_FP(x)   ((int16_t)((x) * 256.0))

// Convert 8.8 fixed-point back to double (for printing)
#define FROM_FP(x) ((double)(x) / 256.0)


// Forward declaration
static int check_output(const char *test_name);


// =============================================================================
// RoCC instruction wrappers
// =============================================================================

// CONFIG: rs1 = kernel_size, rs2 = output_addr, returns success/error in rd
static inline uint64_t conv_config(uint64_t kernel_size, void *output_addr) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(ROCC_X, ret,
                         kernel_size, (uint64_t)output_addr,
                         FUNCT_CONFIG);
    return ret;
}

// LOAD: rs1 = input_addr, rs2 = kernel_addr, returns success/error in rd
static inline uint64_t conv_load(void *input_addr, void *kernel_addr) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(ROCC_X, ret,
                         (uint64_t)input_addr, (uint64_t)kernel_addr,
                         FUNCT_LOAD);
    return ret;
}

// COMPUTE: no operands, returns success/error in rd
static inline uint64_t conv_compute(void) {
    uint64_t ret;
    ROCC_INSTRUCTION_D(ROCC_X, ret, FUNCT_COMPUTE);
    return ret;
}

// STORE: no operands, returns success/error in rd
static inline uint64_t conv_store(void) {
    uint64_t ret;
    ROCC_INSTRUCTION_D(ROCC_X, ret, FUNCT_STORE);
    return ret;
}






// =============================================================================
//golden reference用CPU计算的结果sw——out
// =============================================================================
static void sw_conv(
    const int16_t *input,       // INPUT_SIZE x INPUT_SIZE
    const int16_t *kernel,      // kernel_size x kernel_size
    int16_t       *output,      // INPUT_SIZE x INPUT_SIZE
    int            kernel_size)
{
    int radius = kernel_size / 2;

    for (int row = 0; row < INPUT_SIZE; row++) {
        for (int col = 0; col < INPUT_SIZE; col++) {
            int32_t sum = 0;

            for (int kr = 0; kr < kernel_size; kr++) {
                for (int kc = 0; kc < kernel_size; kc++) {
                    int in_row = row + kr - radius;
                    int in_col = col + kc - radius;

                    int16_t in_val = 0;   // zero padding
                    if (in_row >= 0 && in_row < INPUT_SIZE &&
                        in_col >= 0 && in_col < INPUT_SIZE) {
                        in_val = input[in_row * INPUT_SIZE + in_col];
                    }

                    int16_t ker_val = kernel[kr * kernel_size + kc];

                    // 8.8 x 8.8 -> shift right 8 to stay in 8.8
                    sum += ((int32_t)in_val * (int32_t)ker_val) >> 8;
                }
            }

            // Truncate to lower 16 bits (matches macOut16 in Scala)
            output[row * INPUT_SIZE + col] = (int16_t)(sum & 0xFFFF);
        }
    }
}

// =============================================================================
// Test data buffers (static so they land in BSS/data, not stack)
// =============================================================================
static int16_t input_buf [INPUT_ELEMS]    __attribute__((aligned(64)));
static int16_t kernel_buf[5 * 5]          __attribute__((aligned(64)));
static int16_t hw_output [INPUT_ELEMS]    __attribute__((aligned(64)));
static int16_t sw_output [INPUT_ELEMS]    __attribute__((aligned(64)));




static inline uint64_t read_cycle(void) {
    uint64_t cycle;
    asm volatile ("rdcycle %0" : "=r"(cycle));
    return cycle;
}
// =============================================================================
// Run one complete test: fill buffers, run HW + SW, compare
// =============================================================================

//生成原始矩阵（aij=i+j)和kernel（kij只在中心为1，即图像不变）
static int run_test(const char *name, int kernel_size) {
    memset(hw_output, 0, sizeof(hw_output));
    
    printf("\n=== %s (kernel=%dx%d) ===\n", name, kernel_size, kernel_size);

    // --- Fill input: value at (row, col) = row + col (in 8.8 fixed-point) ---
    for (int row = 0; row < INPUT_SIZE; row++)
        for (int col = 0; col < INPUT_SIZE; col++)
            input_buf[row * INPUT_SIZE + col] = TO_FP(row + col);

    // --- Fill kernel: identity-like, centre = 1.0, rest = 0 (for 1x1 always 1) ---
   // int k_elems = kernel_size * kernel_size;
    memset(kernel_buf, 0, sizeof(kernel_buf));
    if (kernel_size == 1) {
        kernel_buf[0] = TO_FP(1.0);
    } else {
        // Centre element = 1.0, others stay 0 (acts as identity convolution)
        int centre = (kernel_size / 2) * kernel_size + (kernel_size / 2);
        kernel_buf[centre] = TO_FP(1.0);
    }




    //RUN并且测试性能（用print方式呈现，不返回）
    // // --- Software reference ---
    // sw_conv(input_buf, kernel_buf, sw_output, kernel_size);

    // --- Hardware accelerator (with cycle count) ---
     uint64_t t0, t1;
    uint64_t ret;
    t0 = read_cycle();
 
    ret = conv_config((uint64_t)kernel_size, hw_output);
    if (!ret) { printf("[%s] CONFIG failed\n", name); return 0; }
 
    ret = conv_load(input_buf, kernel_buf);
    if (!ret) { printf("[%s] LOAD failed\n", name); return 0; }
 
    ret = conv_compute();
    if (!ret) { printf("[%s] COMPUTE failed\n", name); return 0; }
 
    ret = conv_store();
    if (!ret) { printf("[%s] STORE failed\n", name); return 0; }
 
    t1 = read_cycle();
    
    uint64_t hw_cycles = t1 - t0;
 
    // Fence: ensure CPU sees accelerator's memory writes
    asm volatile("fence" ::: "memory");
 
    // --- Software reference (with cycle count) ---
    t0 = read_cycle();
    sw_conv(input_buf, kernel_buf, sw_output, kernel_size);
    t1 = read_cycle();
    uint64_t sw_cycles = t1 - t0;
 
    // --- Performance report ---
    printf("  HW cycles: %lu\n", (unsigned long)hw_cycles);
    printf("  SW cycles: %lu\n", (unsigned long)sw_cycles);
    if (hw_cycles > 0)
        printf("  Speedup:   %lu x\n", (unsigned long)(sw_cycles / hw_cycles));
 
    // --- Correctness check ---
    return check_output(name);
}
 

// =============================================================================
// Performance measurement using rdcycle
// =============================================================================




// static void perf_test(int kernel_size) {
//     printf("\n=== Performance: kernel=%dx%d ===\n", kernel_size, kernel_size);

//     // Use the same input and kernel as the last run_test call
//     // (buffers are still filled from previous test)

//     uint64_t t0, t1;




//     // Hardware
//     t0 = read_cycle();
//     conv_config((uint64_t)kernel_size, hw_output);
//     conv_load(input_buf, kernel_buf);
//     conv_compute();
//     conv_store();
//     t1 = read_cycle();
//     uint64_t hw_cycles = t1 - t0;

//     // Software
//     t0 = read_cycle();
//     sw_conv(input_buf, kernel_buf, sw_output, kernel_size);
//     t1 = read_cycle();
//     uint64_t sw_cycles = t1 - t0;

//     printf("  HW cycles: %llu\n", (unsigned long long)hw_cycles);
//     printf("  SW cycles: %llu\n", (unsigned long long)sw_cycles);
//     if (hw_cycles > 0)
//         printf("  Speedup:   %.2fx\n", (double)sw_cycles / (double)hw_cycles);
// }


// =============================================================================
// Compare hw and sw output, print first mismatch if any
// =============================================================================
static int check_output(const char *test_name) {

    
    // 打印前9个元素对比
    printf("[%s] First 8 outputs:\n", test_name);
    printf("  idx | sw_output        | hw_output\n");
    for (int i = 0; i < 9; i++) {
        printf("  [%d] | 0x%04x (%.4f) | 0x%04x (%.4f)\n",
               i,
               (uint16_t)sw_output[i], FROM_FP(sw_output[i]),
               (uint16_t)hw_output[i], FROM_FP(hw_output[i]));
    
        }
    
  
    int pass = 1;
    for (int i = 0; i < INPUT_ELEMS; i++) {
        if (hw_output[i] != sw_output[i]) {
            printf("[%s] MISMATCH at [%d][%d]: hw=0x%04x (%.4f) sw=0x%04x (%.4f)\n",
                   test_name,
                   i / INPUT_SIZE, i % INPUT_SIZE,
                   (uint16_t)hw_output[i], FROM_FP(hw_output[i]),
                   (uint16_t)sw_output[i], FROM_FP(sw_output[i]));
            pass = 0;
            break;   // stop at first mismatch
        }
    }
    if (pass)
        printf("[%s] PASS - all %d outputs match\n", test_name, INPUT_ELEMS);
    return pass;
}

// =============================================================================
// Main
// =============================================================================
int main(void) {
    printf("=== ELEN90093 Convolution Accelerator Test ===\n");

    int all_pass = 1;

    // Functional tests
    all_pass &= run_test("Test1_1x1", 1);
    all_pass &= run_test("Test2_3x3", 3);
    all_pass &= run_test("Test3_5x5", 5);

    // Performance measurement (uses 5x5 buffers from last test)
   // perf_test(5);

    printf("\n=== Final Result: %s ===\n", all_pass ? "ALL PASS" : "SOME FAILED");
    return 0;
}