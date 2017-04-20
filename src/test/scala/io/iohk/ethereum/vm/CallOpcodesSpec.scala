package io.iohk.ethereum.vm

import akka.util.ByteString
import org.scalatest.{Matchers, WordSpec}
import Assembly._
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.domain.{Account, Address}
import io.iohk.ethereum.utils.ByteUtils
import io.iohk.ethereum.vm.MockWorldState._

// scalastyle:off object.name
class CallOpcodesSpec extends WordSpec with Matchers {

  val config = EvmConfig.PostEIP160Config

  import config.feeSchedule._

  object fxt {

    val ownerAddr = Address(0xcafebabe)
    val extAddr = Address(0xfacefeed)
    val callerAddr = Address(0xdeadbeef)

    val ownerOffset = UInt256(0)
    val callerOffset = UInt256(1)
    val valueOffset = UInt256(2)

    val extCode = Assembly(
      //store owner address
      ADDRESS,
      PUSH1, ownerOffset.toInt,
      SSTORE,

      //store caller address
      CALLER,
      PUSH1, callerOffset.toInt,
      SSTORE,

      //store call value
      CALLVALUE,
      PUSH1, valueOffset.toInt,
      SSTORE,

      // return first half of unmodified input data
      PUSH1, 2,
      CALLDATASIZE,
      DIV,
      PUSH1, 0,
      DUP2,
      DUP2,
      DUP1,
      CALLDATACOPY,
      RETURN
    )

    val inputData = Generators.getUInt256Gen().sample.get.bytes
    val expectedMemCost = config.calcMemCost(inputData.size, inputData.size, inputData.size / 2)

    val initialBalance = UInt256(1000)

    val requiredGas = {
      val storageCost = 3 * G_sset
      val memCost = config.calcMemCost(0, 0, 32)
      val copyCost = G_copy * wordsForBytes(32)

      extCode.linearConstGas(config) + storageCost + memCost + copyCost
    }

    val gasMargin = 13

    val initialOwnerAccount = Account(balance = initialBalance)

    val extProgram = extCode.program
    val invalidProgram = Program(extProgram.code.init :+ INVALID.code)

    val worldWithoutExtAccount = MockWorldState().saveAccount(ownerAddr, initialOwnerAccount)
    val worldWithExtAccount = worldWithoutExtAccount.saveAccount(extAddr, Account.Empty)
      .saveCode(extAddr, extProgram.code)
    val worldWithInvalidProgram = worldWithoutExtAccount.saveAccount(extAddr, Account.Empty)
      .saveCode(extAddr, invalidProgram.code)

    val env = ExecEnv(ownerAddr, callerAddr, callerAddr, 1, ByteString.empty, 123, Program(ByteString.empty), null, 0)
    val context: PC = ProgramContext(env, ownerAddr, 2 * requiredGas, worldWithExtAccount, config)
  }

  case class CallResult(
    op: CallOp,
    context: ProgramContext[MockWorldState, MockStorage] = fxt.context,
    inputData: ByteString = fxt.inputData,
    gas: UInt256 = fxt.requiredGas + fxt.gasMargin,
    to: Address = fxt.extAddr,
    value: UInt256 = fxt.initialBalance / 2,
    inOffset: UInt256 = UInt256.Zero,
    inSize: UInt256 = fxt.inputData.size,
    outOffset: UInt256 = fxt.inputData.size,
    outSize: UInt256 = fxt.inputData.size / 2
  ) {
    private val params = Seq(gas, to.toUInt256, value, inOffset, inSize, outOffset, outSize).reverse

    private val paramsForDelegate = params.take(4) ++ params.drop(5)

    private val stack = Stack.empty().push(if (op == DELEGATECALL) paramsForDelegate else params)
    private val mem = Memory.empty.store(UInt256.Zero, inputData)

    val stateIn: PS = ProgramState(context).withStack(stack).withMemory(mem)
    val stateOut: PS = op.execute(stateIn)
    val world: MockWorldState = stateOut.world

    val ownBalance: UInt256 = world.getBalance(context.env.ownerAddr)
    val extBalance: UInt256 = world.getBalance(to)

    val ownStorage: MockStorage = world.getStorage(context.env.ownerAddr)
    val extStorage: MockStorage = world.getStorage(to)
  }

  "CALL" when {
    "external contract terminates normally" should {

      val call = CallResult(op = CALL)

      "update external account's storage" in {
        call.ownStorage shouldEqual MockStorage.Empty
        call.extStorage.data.size shouldEqual 3
      }

      "update external account's balance" in {
        call.extBalance shouldEqual call.value
        call.ownBalance shouldEqual fxt.initialBalance - call.value
      }

      "pass correct addresses and value" in {
        Address(call.extStorage.load(fxt.ownerOffset)) shouldEqual fxt.extAddr
        Address(call.extStorage.load(fxt.callerOffset)) shouldEqual fxt.ownerAddr
        call.extStorage.load(fxt.valueOffset) shouldEqual call.value
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.One
      }

      "consume correct gas (refund unused gas)" in {
        val expectedGas = fxt.requiredGas - G_callstipend + G_call + G_callvalue + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "call depth limit is reached" should {

      val context: PC = fxt.context.copy(env = fxt.env.copy(callDepth = EvmConfig.MaxCallDepth))
      val call = CallResult(op = CALL, context = context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithExtAccount
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume correct gas (refund call gas)" in {
        val expectedGas = G_call + G_callvalue - G_callstipend + config.calcMemCost(32, 32, 16)
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "call value is greater than balance" should {

      val call = CallResult(op = CALL, value = fxt.initialBalance + 1)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithExtAccount
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume correct gas (refund call gas)" in {
        val expectedGas = G_call + G_callvalue - G_callstipend + config.calcMemCost(32, 32, 16)
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "call value is zero" should {
      val call = CallResult(op = CALL, value = 0)

      "adjust gas cost" in {
        val expectedGas = fxt.requiredGas + G_call + fxt.expectedMemCost - (G_sset - G_sreset)
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "external contract terminates abnormally" should {

      val context: PC = fxt.context.copy(world = fxt.worldWithInvalidProgram)
      val call = CallResult(op = CALL, context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithInvalidProgram
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume all call gas" in {
        val expectedGas = fxt.requiredGas + fxt.gasMargin + G_call + G_callvalue + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "calling a non-existent account" should {

      val context: PC = fxt.context.copy(world = fxt.worldWithoutExtAccount)
      val call = CallResult(op = CALL, context)

      "create new account and add to its balance" in {
        call.extBalance shouldEqual call.value
        call.ownBalance shouldEqual fxt.initialBalance - call.value
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.One
      }

      "consume correct gas (refund call gas, add new account modifier)" in {
        val expectedGas = G_call + G_callvalue + G_newaccount - G_callstipend + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "calling a precompiled contract" should {
      val contractAddress = Address(1) // ECDSA recovery
      val invalidSignature = ByteString(Array.fill(128)(0.toByte))
      val world = fxt.worldWithoutExtAccount.saveAccount(contractAddress, Account(balance = 1))
      val context: PC = fxt.context.copy(world = world)
      val call = CallResult(op = CALL, context = context, to = contractAddress, inputData = invalidSignature,
        inOffset = 0, inSize = 128, outOffset = 0, outSize = 128
      )

      "compute a correct result" in {
        // For invalid signature the return data should be empty, so the memory should not be modified.
        // This is more interesting than checking valid signatures which are tested elsewhere
        val (result, _) = call.stateOut.memory.load(call.outOffset, call.outSize)
        val expected = invalidSignature

        result shouldEqual expected
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.One
      }

      "update precompiled contract's balance" in {
        call.extBalance shouldEqual call.value + 1
        call.ownBalance shouldEqual fxt.initialBalance - call.value
      }

      "consume correct gas" in {
        val contractCost = UInt256(3000)
        val expectedGas = contractCost - G_callstipend + G_call + G_callvalue // memory not increased
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }
  }

  "CALLCODE" when {
    "external code terminates normally" should {
      val call = CallResult(op = CALLCODE)

      "update own account's storage" in {
        call.extStorage shouldEqual MockStorage.Empty
        call.ownStorage.data.size shouldEqual 3
      }

      "not update any account's balance" in {
        call.extBalance shouldEqual UInt256.Zero
        call.ownBalance shouldEqual fxt.initialBalance
      }

      "pass correct addresses and value" in {
        Address(call.ownStorage.load(fxt.ownerOffset)) shouldEqual fxt.ownerAddr
        Address(call.ownStorage.load(fxt.callerOffset)) shouldEqual fxt.ownerAddr
        call.ownStorage.load(fxt.valueOffset) shouldEqual call.value
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256(1)
      }

      "consume correct gas (refund unused gas)" in {
        val expectedGas = fxt.requiredGas - G_callstipend + G_call + G_callvalue + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "call depth limit is reached" should {

      val context: PC = fxt.context.copy(env = fxt.env.copy(callDepth = EvmConfig.MaxCallDepth))
      val call = CallResult(op = CALLCODE, context = context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithExtAccount
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume correct gas (refund call gas)" in {
        val expectedGas = G_call + G_callvalue - G_callstipend + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "call value is greater than balance" should {

      val call = CallResult(op = CALLCODE, value = fxt.initialBalance + 1)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithExtAccount
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume correct gas (refund call gas)" in {
        val expectedGas = G_call + G_callvalue - G_callstipend + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "call value is zero" should {
      val call = CallResult(op = CALL, value = 0)

      "adjust gas cost" in {
        val expectedGas = fxt.requiredGas + G_call + fxt.expectedMemCost - (G_sset - G_sreset)
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "external code terminates abnormally" should {
      val context: PC = fxt.context.copy(world = fxt.worldWithInvalidProgram)
      val call = CallResult(op = CALLCODE, context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithInvalidProgram
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume all call gas" in {
        val expectedGas = fxt.requiredGas + fxt.gasMargin + G_call + G_callvalue + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "external account does not exist" should {
      val context: PC = fxt.context.copy(world = fxt.worldWithoutExtAccount)
      val call = CallResult(op = CALLCODE, context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithoutExtAccount
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256(1)
      }

      "consume correct gas (refund call gas)" in {
        val expectedGas = G_call + G_callvalue - G_callstipend + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "calling a precompiled contract" should {
      val contractAddress = Address(2) // SHA256
      val inputData = ByteString(Array.fill(128)(1.toByte))
      val world = fxt.worldWithoutExtAccount.saveAccount(contractAddress, Account(balance = 1))
      val context: PC = fxt.context.copy(world = world)
      val call = CallResult(op = CALLCODE, context = context, to = contractAddress, inputData = inputData,
        inOffset = 0, inSize = 128, outOffset = 128, outSize = 32
      )

      "compute a correct result" in {
        val (result, _) = call.stateOut.memory.load(call.outOffset, call.outSize)
        val expected = kec256(inputData)

        result shouldEqual expected
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.One
      }

      "not update precompiled contract's balance" in {
        call.extBalance shouldEqual 1
        call.ownBalance shouldEqual fxt.initialBalance
      }

      "consume correct gas" in {
        val contractCost = 60 + 12 * wordsForBytes(inputData.size)
        val expectedGas = contractCost - G_callstipend + G_call + G_callvalue + config.calcMemCost(128, 128, 32)
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }
  }

  "DELEGATECALL" when {
    "external code terminates normally" should {
      val call = CallResult(op = DELEGATECALL)

      "update own account's storage" in {
        call.extStorage shouldEqual MockStorage.Empty
        call.ownStorage.data.size shouldEqual 3
      }

      "not update any account's balance" in {
        call.extBalance shouldEqual UInt256.Zero
        call.ownBalance shouldEqual fxt.initialBalance
      }

      "pass correct addresses and value" in {
        Address(call.ownStorage.load(fxt.ownerOffset)) shouldEqual fxt.ownerAddr
        Address(call.ownStorage.load(fxt.callerOffset)) shouldEqual fxt.env.callerAddr
        call.ownStorage.load(fxt.valueOffset) shouldEqual fxt.env.value
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256(1)
      }

      "consume correct gas (refund unused gas)" in {
        val expectedGas = fxt.requiredGas + G_call + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "call depth limit is reached" should {

      val context: PC = fxt.context.copy(env = fxt.env.copy(callDepth = EvmConfig.MaxCallDepth))
      val call = CallResult(op = DELEGATECALL, context = context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithExtAccount
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume correct gas (refund call gas)" in {
        val expectedGas = G_call + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "external code terminates abnormally" should {
      val context: PC = fxt.context.copy(world = fxt.worldWithInvalidProgram)
      val call = CallResult(op = DELEGATECALL, context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithInvalidProgram
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume all call gas" in {
        val expectedGas = fxt.requiredGas + fxt.gasMargin + G_call + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "external account does not exist" should {
      val context: PC = fxt.context.copy(world = fxt.worldWithoutExtAccount)
      val call = CallResult(op = DELEGATECALL, context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithoutExtAccount
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256(1)
      }

      "consume correct gas (refund call gas)" in {
        val expectedGas = G_call + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "calling a precompiled contract" should {
      val contractAddress = Address(3) // RIPEMD160
      val inputData = ByteString(Array.fill(128)(1.toByte))
      val world = fxt.worldWithoutExtAccount.saveAccount(contractAddress, Account(balance = 1))
      val context: PC = fxt.context.copy(world = world)
      val call = CallResult(op = DELEGATECALL, context = context, to = contractAddress, inputData = inputData,
        inOffset = 0, inSize = 128, outOffset = 128, outSize = 32
      )

      "compute a correct result" in {
        val (result, _) = call.stateOut.memory.load(call.outOffset, call.outSize)
        val expected = ByteUtils.padLeft(ripemd160(inputData), 32)

        result shouldEqual expected
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.One
      }

      "not update precompiled contract's balance" in {
        call.extBalance shouldEqual 1
        call.ownBalance shouldEqual fxt.initialBalance
      }

      "consume correct gas" in {
        val contractCost = 600 + 120 * wordsForBytes(inputData.size)
        val expectedGas = contractCost + G_call + config.calcMemCost(128, 128, 20)
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }
  }

}