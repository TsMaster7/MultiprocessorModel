package cachemodeler_multyprocessor;

/**
 *
 * @author Taras
 */

import javax.swing.JTextArea;

//класс для подсчета коэффициента загрузки процессоров и всей системы
public class ProcessorMonitor implements Runnable
{
    JTextArea resultText;
    //массив обслуживаемых процессоров
    private Processor[] processors;
    //счетчики холостых обращений
    private long[] unloadTacts; 
    //счетчики загруженных обращений
    private long[] loadTacts;
    //массив коэффициентов загрузки
    private double[] loadFactors;
    //поток для монитора
    public Thread processorMonitorThread;
    private Multyprocessor multyproc;
    
    public ProcessorMonitor(Processor[] processors, Multyprocessor mp, JTextArea resText)
    {
        this.processors = processors;
        this.multyproc = mp;
        this.resultText = resText;        
        loadFactors = new double[processors.length + 1];
        processorMonitorThread = new Thread(this, "monitor_thread");
    }
    
    @Override
    public void run()
    {
        while (true)
        {
            //если все процессоры работают, опрашиваем их состояние  
            //иначе останавливаем поток
            boolean allRun = false;
            for (int i=0;  i<processors.length; i++)
            {
                allRun = allRun || processors[i].getProcessorThread().isAlive();
            }
            if (!allRun) 
            {
                resultText.append("Моделирование закончено: " + java.util.Calendar.getInstance().getTime() + "\n");
                
                double[] hitRates = this.multyproc.getHitRate();
                double[] loadings = this.multyproc.getSystemLoading();
                double[] memCycle = this.multyproc.getAvgMemoryCycleTime();
                
                resultText.append(" Загрузка: \n");
                //int i;
                for (int i=0; i<loadings.length-1; i++)
                {
                    resultText.append("  - Процессор " + i + ": " + loadings[i] + "%\n");
                }
                resultText.append("  -- Вся система: " + loadings[loadings.length-1] + "%\n");
                
                resultText.append(" Коэффициент кэш-попаданий: \n");
                for (int i=0; i<hitRates.length-1; i++)
                {
                    resultText.append("  - Кэш процессора " + i + ": " + hitRates[i] + "%\n");
                }
                resultText.append("  -- Вся кэш-память: " + hitRates[hitRates.length-1] + "%\n");
                
                resultText.append(" Среднее время цикла памяти: \n");
                for (int i=0; i<memCycle.length-1; i++)
                {
                    resultText.append("  - Для процессора " + i +": " + memCycle[i] + "\n");
                }
                resultText.append("  -- Вся система: " + memCycle[memCycle.length-1] + "\n");
                
                resultText.append("  - Производительность системы (на 1000 тактов):\n   - " + multyproc.getSystemPower() + "\n");
                resultText.append("  - Доля разделяемых блоков:\n   - " + multyproc.getSystemBus().getSharedQueriesCount() + "%\n");
                
                if (this.multyproc.isMSI())
                {
                    int queriesToMBlockCount = multyproc.getSystemBus().getMStatesCounter();
                    int queriesToSBlockCount = multyproc.getSystemBus().getSStatesCounter();
                    int queriesToIBlockCount = multyproc.getSystemBus().getIStatesCounter();
                    double MProb = (double)queriesToMBlockCount / 
                            (queriesToMBlockCount + queriesToSBlockCount + queriesToIBlockCount);
                    double SProb = (double)queriesToSBlockCount / 
                            (queriesToMBlockCount + queriesToSBlockCount + queriesToIBlockCount);
                    double IProb = (double)queriesToIBlockCount / 
                            (queriesToMBlockCount + queriesToSBlockCount + queriesToIBlockCount);
                    resultText.append("  - Доля блоков в состоянии M:\n   - " + MProb*100 + "%\n");
                    resultText.append("  - Доля блоков в состоянии S:\n   - " + SProb*100 + "%\n");
                    resultText.append("  - Доля блоков в состоянии I:\n   - " + IProb*100 + "%\n");
                }
                if (this.multyproc.isMESI())
                {
                    int queriesToMBlockCount = multyproc.getSystemBus().getMStatesCounter();
                    int queriesToEBlockCount = multyproc.getSystemBus().getEStatesCounter();
                    int queriesToSBlockCount = multyproc.getSystemBus().getSStatesCounter();
                    int queriesToIBlockCount = multyproc.getSystemBus().getIStatesCounter();
                    double MProb = (double)queriesToMBlockCount / 
                            (queriesToMBlockCount + queriesToSBlockCount + queriesToIBlockCount + queriesToEBlockCount);
                    double EProb = (double)queriesToEBlockCount / 
                            (queriesToMBlockCount + queriesToSBlockCount + queriesToIBlockCount + queriesToEBlockCount);
                    double SProb = (double)queriesToSBlockCount / 
                            (queriesToMBlockCount + queriesToSBlockCount + queriesToIBlockCount + queriesToEBlockCount);
                    double IProb = (double)queriesToIBlockCount / 
                            (queriesToMBlockCount + queriesToSBlockCount + queriesToIBlockCount + queriesToEBlockCount);
                    resultText.append("  - Доля блоков в состоянии M:\n   - " + MProb*100 + "%\n");
                    resultText.append("  - Доля блоков в состоянии S:\n   - " + SProb*100 + "%\n");
                    resultText.append("  - Доля блоков в состоянии E:\n   - " + EProb*100 + "%\n");
                    resultText.append("  - Доля блоков в состоянии I:\n   - " + IProb*100 + "%\n");
                }
                
                        
                processorMonitorThread.stop();
                multyproc.modelingRun = false;
                break;
            }
            else
            {               
                try
                {
                    processorMonitorThread.sleep(50);
                }
                catch (InterruptedException ex) {}
            }
        }
    }
    
    //подсчет коэффицинтов загрузки
    public double[] getLoadFactors()
    {
        double fullLoad = 0;
        for (int i=0;  i<processors.length; i++)
        {
            loadFactors[i] = (double)processors[i].getLoadCount() / 
                    (processors[i].getLoadCount() + processors[i].getUnloadCount()) * 100;
            fullLoad += loadFactors[i];
        }
        fullLoad /= processors.length;       
        loadFactors[processors.length] = fullLoad;
        return loadFactors;
    }
    
    public void start()
    {
        processorMonitorThread.start();
    }
}
